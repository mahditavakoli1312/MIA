package ir.mahditavakoli.mia.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.data.repository.CommandClassification
import ir.mahditavakoli.mia.data.repository.GeminiVoiceIntentClassifier
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.data.repository.IntentExecutionRepository
import ir.mahditavakoli.mia.data.repository.IssueTaskSync
import ir.mahditavakoli.mia.data.repository.LocalSpendStore
import ir.mahditavakoli.mia.data.repository.OxTextIntentClassifier
import ir.mahditavakoli.mia.data.repository.ProjectRepository
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.Reasoning
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull
import ir.mahditavakoli.mia.network.openrouter.providerFor
import ir.mahditavakoli.mia.network.toPersianMessage
import ir.mahditavakoli.mia.notify.AgentCompletionWorker
import ir.mahditavakoli.mia.voice.VoiceRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates both ways a command can reach MIA, which converge as soon as they are intents:
 *
 *  - **Voice:** mic -> recorded audio -> Gemini (multimodal transcription + intent extraction).
 *  - **Text:** typed Persian -> on-device normalization -> the model chosen in Settings
 *    (OpenRouter's free `minimax/minimax-m3:free` by default, or MiniMax M3 on the user's own
 *    account) for prompt pre-processing -> intent extraction.
 *
 * From there both run the same path: Supabase execution -> refreshed project list. Plain
 * [AndroidViewModel] — the default Compose `viewModel()` factory wires the Application instance
 * automatically, no custom ViewModelProvider.Factory needed.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = NetworkModule.secretStore
    private val voiceRecorder = VoiceRecorder(application)
    private val voiceIntentClassifier = GeminiVoiceIntentClassifier(
        api = NetworkModule.geminiApi,
        json = NetworkModule.json,
        apiKeyProvider = { secretStore.geminiApiKey }
    )
    /**
     * Built per command rather than once, because the model is a live Settings choice: a user
     * who switches to MiniMax after hitting the free tier's daily wall expects the very next
     * command to go there, not the next app start. Constructing it is a few field reads — the
     * Retrofit clients behind it are the cached singletons in [NetworkModule].
     */
    private fun textIntentClassifier(): OxTextIntentClassifier {
        val model = secretStore.textModelId
        val provider = providerFor(model)
        return OxTextIntentClassifier(
            api = NetworkModule.chatCompleterFor(provider),
            json = NetworkModule.json,
            apiKeyProvider = { secretStore.apiKeyFor(provider) },
            fallbackApiKeyProvider = { secretStore.fallbackApiKeyFor(provider) },
            model = model,
            provider = provider,
            // MiniMax's own endpoint has no `reasoning` block — it accepts and ignores one, and
            // returns the trace in a separate field regardless. Sending it would be noise.
            reasoning = if (provider == AgentProvider.MINIMAX) null else Reasoning.MAX
        )
    }
    private val gitHubRepository = NetworkModule.gitHubRepository
    private val intentExecutionRepository = IntentExecutionRepository(NetworkModule.supabaseApi, gitHubRepository)
    private val projectRepository = ProjectRepository(NetworkModule.supabaseApi)
    private val issueTaskSync = IssueTaskSync(NetworkModule.supabaseApi)

    /**
     * MIA's own spend, kept locally because nothing else records it.
     *
     * The agents' spend survives on GitHub as commit trailers and comment footers, but a command
     * that opens no issue — a rename, a deletion, a command the user then cancels — bills Gemini
     * and leaves no trace anywhere the spend screen can read.
     */
    private val localSpendStore = LocalSpendStore(application)

    private val _uiState = MutableStateFlow(
        MainUiState(
            isGitHubConfigured = NetworkModule.isGitHubConfigured,
            agentHandledByDefault = secretStore.agentHandledByDefault,
            confirmBeforeExecute = secretStore.confirmBeforeExecute,
            geminiApiKey = secretStore.geminiApiKeyOverride,
            openRouterApiKey = secretStore.agentApiKeyOverride,
            openRouterFallbackApiKey = secretStore.agentFallbackApiKeyOverride,
            miniMaxApiKey = secretStore.miniMaxApiKeyOverride,
            textModelId = secretStore.textModelId
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val micAmplitude: StateFlow<Float> = voiceRecorder.amplitude

    // One-shot user-facing messages (snackbar) — a Channel avoids re-showing the same
    // message on rotation/recomposition the way a plain StateFlow field would.
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Fires the one time a command actually hands work to the agent, so the screen can ask for
     * POST_NOTIFICATIONS then.
     *
     * That is the moment the permission means something: the user has just started work that
     * finishes somewhere else, later. Asking at launch would be a dialog about a feature they
     * have not used yet, and Android only gives one refusal before the prompt stops appearing.
     * A [Channel] rather than state so a refused prompt is not re-shown on every rotation.
     */
    private val _notificationPermissionRequests = Channel<Unit>(Channel.CONFLATED)
    val notificationPermissionRequests = _notificationPermissionRequests.receiveAsFlow()

    // Cancelled and restarted on every project refresh, so a slow sweep over an old project
    // list can't keep writing counts for cards that are no longer on screen.
    private var issueSummaryJob: Job? = null

    init {
        refreshProjects()
        verifyGitHubToken()
    }

    // Settings ----------------------------------------------------------------

    /** Toggle whether new voice-created tasks are handed to the agent. Persisted immediately. */
    fun onAgentHandledChange(enabled: Boolean) {
        secretStore.agentHandledByDefault = enabled
        _uiState.update { it.copy(agentHandledByDefault = enabled) }
    }

    /** Toggle the pre-execution confirmation sheet. Persisted immediately. */
    fun onConfirmBeforeExecuteChange(enabled: Boolean) {
        secretStore.confirmBeforeExecute = enabled
        _uiState.update { it.copy(confirmBeforeExecute = enabled) }
    }

    fun onGeminiApiKeyChange(value: String) {
        _uiState.update { it.copy(geminiApiKey = value) }
    }

    /** Persist the Gemini API key entered in Settings (used for on-device voice→intent). */
    fun saveGeminiApiKey() {
        secretStore.saveGeminiApiKey(_uiState.value.geminiApiKey)
        emitEvent("کلید Gemini ذخیره شد")
    }

    fun onOpenRouterApiKeyChange(value: String) {
        _uiState.update { it.copy(openRouterApiKey = value) }
    }

    /**
     * Persist the OpenRouter API key. One key, two uses: MIA's own typed-command pipeline
     * (`minimax/minimax-m3:free`) and the per-repo `OPENROUTER_API_KEY` Actions secret the CI
     * agent runs on.
     */
    fun saveOpenRouterApiKey() {
        secretStore.saveAgentApiKey(_uiState.value.openRouterApiKey)
        emitEvent("کلید OpenRouter ذخیره شد")
    }

    fun onOpenRouterFallbackApiKeyChange(value: String) {
        _uiState.update { it.copy(openRouterFallbackApiKey = value) }
    }

    /**
     * Persist the spare OpenRouter key. It is never used until the primary one reports a limit
     * (429 / 402) — in the app's typed-command pipeline, and in each repo as the
     * `OPENROUTER_API_KEY_FALLBACK` Actions secret the AI-team workflows fall back to.
     */
    fun saveOpenRouterFallbackApiKey() {
        secretStore.saveAgentFallbackApiKey(_uiState.value.openRouterFallbackApiKey)
        emitEvent("کلید پشتیبان OpenRouter ذخیره شد")
    }

    fun onMiniMaxApiKeyChange(value: String) {
        _uiState.update { it.copy(miniMaxApiKey = value) }
    }

    /**
     * Persist the MiniMax platform key. One key, two uses, exactly like the OpenRouter one: the
     * `MiniMax-M3` option in MIA's own typed-command pipeline, and the per-repo `MINIMAX_API_KEY`
     * Actions secret a repo pointed at a MiniMax model runs its AI team on.
     */
    fun saveMiniMaxApiKey() {
        secretStore.saveMiniMaxApiKey(_uiState.value.miniMaxApiKey)
        emitEvent("کلید MiniMax ذخیره شد")
    }

    /**
     * Switch the model MIA's own typed commands run on. Persisted immediately and picked up by
     * the next command — this is a device preference and does not touch any repo, which is what
     * the per-project model screen is for.
     */
    fun onTextModelSelected(modelId: String) {
        secretStore.textModelId = modelId
        _uiState.update { it.copy(textModelId = secretStore.textModelId) }
        val label = agentModelOrNull(modelId)?.label ?: modelId
        emitEvent("دستورهای متنی از این پس با «$label» پردازش می‌شوند")
    }

    // Warn when the GitHub token can't push workflow files (missing `workflow` scope).
    private fun verifyGitHubToken() {
        if (!gitHubRepository.isConfigured) return
        viewModelScope.launch {
            gitHubRepository.verifyTokenScopes().onSuccess { check ->
                if (check.determinable && !check.hasWorkflow) {
                    emitEvent(
                        "توکن گیت‌هاب دسترسی «workflow» ندارد؛ بارگذاری فایل ورک‌فلوی ایجنت ناموفق خواهد بود."
                    )
                }
            }
        }
    }

    fun onMicClick() {
        when (_uiState.value.recordingState) {
            // Second tap: stop recording and hand the audio to Gemini.
            is RecordingState.Listening -> stopAndProcess()
            // Ignore taps while a previous command is still being classified/executed.
            is RecordingState.Processing -> Unit
            is RecordingState.Idle -> startListening()
        }
    }

    private fun startListening() {
        if (voiceRecorder.start()) {
            _uiState.update { it.copy(recordingState = RecordingState.Listening) }
        } else {
            emitEvent("دسترسی به میکروفون ممکن نشد")
        }
    }

    private fun stopAndProcess() {
        val audio = voiceRecorder.stop()
        if (audio == null) {
            _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            emitEvent("صدایی ضبط نشد، دوباره تلاش کنید")
            return
        }
        startTimeline(refining = false)
        _uiState.update {
            it.copy(recordingState = RecordingState.Processing, stage = CommandStage.UNDERSTANDING)
        }
        viewModelScope.launch {
            // Pass the currently-loaded projects so Gemini can resolve spoken names
            // against real data instead of guessing from the audio alone.
            handleClassification(
                result = voiceIntentClassifier.classify(audio, _uiState.value.projects),
                failureMessage = "متوجه دستور نشدم"
            )
        }
    }

    // Text ---------------------------------------------------------------------

    // Timeline ------------------------------------------------------------------

    /** Polls the tracked issue while the main screen is visible; cancelled the moment it isn't. */
    private var timelineJob: Job? = null

    /**
     * Starts a fresh timeline for a command that is just beginning.
     *
     * The previous one is dropped, poll and all: two commands' timelines on screen at once would
     * make it impossible to tell which agent run the rows belong to, and the newer command is the
     * one the user is watching.
     */
    private fun startTimeline(refining: Boolean) {
        timelineJob?.cancel()
        timelineJob = null
        _uiState.update {
            it.copy(timeline = CommandTimeline.starting(now = System.currentTimeMillis(), refining = refining))
        }
    }

    private fun updateTimeline(transform: (CommandTimeline) -> CommandTimeline) {
        _uiState.update { state ->
            val timeline = state.timeline ?: return@update state
            state.copy(timeline = transform(timeline))
        }
    }

    /** Expands or collapses the card. Collapsing a finished run is the point of the summary row. */
    fun onTimelineToggleExpanded() = updateTimeline { it.copy(isExpanded = !it.isExpanded) }

    /** Closes a finished timeline for good. A running one is left alone — see the card. */
    fun onTimelineDismiss() {
        if (_uiState.value.timeline?.isFinished != true) return
        timelineJob?.cancel()
        timelineJob = null
        _uiState.update { it.copy(timeline = null) }
    }

    /** "تلاش دوباره": puts the tracked issue back in the agent's queue and resumes watching. */
    fun onTimelineRetry() {
        val tracked = _uiState.value.timeline?.issue ?: return
        viewModelScope.launch {
            gitHubRepository.issueFor(tracked.projectName, tracked.number)
                .mapCatching { issue -> gitHubRepository.redoIssue(tracked.projectName, issue).getOrThrow() }
                .fold(
                    onSuccess = {
                        emitEvent("ایشو #${tracked.number} دوباره به ایجنت سپرده شد")
                        // The old timeline ended in a failure; watching resumes from a clean set of
                        // remote steps rather than showing a mix of the two attempts.
                        updateTimeline {
                            it.copy(isFinished = false, isExpanded = true, failureReason = null)
                                .handedOff(tracked, otherIssues = 0, now = System.currentTimeMillis())
                        }
                        startTimelinePolling()
                    },
                    onFailure = { error ->
                        emitEvent(error.toPersianMessage("سپردن دوباره به ایجنت ناموفق بود"))
                    }
                )
        }
    }

    /**
     * Watches the tracked issue: every [TIMELINE_POLL_MS] while the screen is showing, and not at
     * all when it isn't.
     *
     * Called from the screen's lifecycle rather than started with the command, because an agent run
     * takes minutes and polling GitHub from a backgrounded app would spend the shared rate limit on
     * a card nobody is looking at.
     */
    fun startTimelinePolling() {
        val tracked = _uiState.value.timeline?.issue ?: return
        if (_uiState.value.timeline?.isFinished == true) return
        if (timelineJob?.isActive == true) return
        timelineJob = viewModelScope.launch {
            while (isActive) {
                val issue = gitHubRepository.issueFor(tracked.projectName, tracked.number).getOrNull()
                // A failed read is a network blip, not an outcome: leave the timeline as it is and
                // try again next tick rather than showing the user a wrong state.
                if (issue != null) {
                    val comments = gitHubRepository
                        .issueCommentsFor(tracked.projectName, tracked.number)
                        .getOrDefault(emptyList())
                    updateTimeline { it.advance(issue, comments, System.currentTimeMillis()) }
                    if (_uiState.value.timeline?.isFinished == true) {
                        // Nothing left to watch. Collapse to the summary row and stop polling.
                        updateTimeline { it.copy(isExpanded = false) }
                        // A merged issue means a task closed and a count changed on its card.
                        refreshIssueSummary(tracked.projectName)
                        return@launch
                    }
                }
                delay(TIMELINE_POLL_MS)
            }
        }
    }

    /** Stops polling — the screen is no longer visible. The timeline itself is kept. */
    fun stopTimelinePolling() {
        timelineJob?.cancel()
        timelineJob = null
    }

    fun onCommandTextChange(value: String) {
        _uiState.update { it.copy(commandText = value) }
    }

    /** Dismisses the "understood as" preview of the last typed command. */
    fun clearRefinedPrompt() {
        _uiState.update { it.copy(refinedPrompt = null) }
    }

    /**
     * Runs a typed command through the same execution path as a spoken one. The text is cleared
     * immediately (so the field is ready for the next command) but kept in scope here, which is
     * what lets a failure put it back for the user to fix instead of losing what they typed.
     */
    fun onSendText() {
        val text = _uiState.value.commandText.trim()
        if (text.isBlank() || _uiState.value.isBusy) return
        startTimeline(refining = true)
        _uiState.update {
            it.copy(
                commandText = "",
                refinedPrompt = null,
                recordingState = RecordingState.Processing,
                stage = CommandStage.REFINING
            )
        }
        viewModelScope.launch {
            val result = textIntentClassifier().classify(
                rawText = text,
                projects = _uiState.value.projects,
                // Show the refined prompt as soon as the pre-processing call lands, rather than
                // holding it back until the whole command has finished executing.
                onRefined = { refined ->
                    _uiState.update {
                        it.copy(refinedPrompt = refined, stage = CommandStage.UNDERSTANDING)
                    }
                    updateTimeline {
                        it.atLocalStage(CommandStage.UNDERSTANDING, System.currentTimeMillis())
                    }
                }
            )
            if (result.isFailure) {
                // Restore the command so the user can edit rather than retype it.
                _uiState.update { it.copy(commandText = text) }
            }
            handleClassification(result, failureMessage = "متوجه دستور نشدم", originalText = text)
        }
    }

    /**
     * The one place a classified command — however it arrived — becomes actions.
     *
     * @param originalText what the user typed, so the confirmation sheet can hand it back if they
     *        would rather reword the command than run it. Null for a spoken one.
     */
    private suspend fun handleClassification(
        result: Result<CommandClassification>,
        failureMessage: String,
        originalText: String? = null
    ) {
        result.fold(
            onSuccess = { classification ->
                // Recorded here rather than after execution: the tokens are already spent by the
                // time the command is understood, whether or not the user goes on to run it.
                classification.usage?.let(localSpendStore::record)
                if (needsConfirmation(classification.intents)) {
                    // The command is no longer in flight — it is waiting on the user — so the
                    // status banner comes down and both front doors are usable again.
                    updateTimeline { it.withUnderstandingCost(classification.usage?.totalTokens) }
                    _uiState.update {
                        it.copy(
                            recordingState = RecordingState.Idle,
                            stage = CommandStage.NONE,
                            refinedPrompt = classification.refinedPrompt,
                            pendingConfirmation = IntentConfirmation(
                                rows = classification.intents.mapIndexed { index, intent ->
                                    ConfirmableIntent(id = index, intent = intent)
                                },
                                originalText = originalText,
                                usage = classification.usage,
                                agentHandled = it.agentHandledByDefault
                            )
                        )
                    }
                    return
                }
                _uiState.update {
                    it.copy(
                        stage = CommandStage.EXECUTING,
                        refinedPrompt = classification.refinedPrompt
                    )
                }
                updateTimeline {
                    it.withUnderstandingCost(classification.usage?.totalTokens)
                        .atLocalStage(CommandStage.EXECUTING, System.currentTimeMillis())
                }
                executeIntents(classification.intents, classification.usage)
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(recordingState = RecordingState.Idle, stage = CommandStage.NONE)
                }
                updateTimeline {
                    it.failedLocally(failureMessage, System.currentTimeMillis())
                }
                emitEvent("$failureMessage: ${error.message}")
            }
        )
    }

    /**
     * Whether this batch is shown for approval first.
     *
     * The Settings switch only governs ordinary commands: a batch containing a delete is always
     * confirmed, whatever the preference says. That asymmetry is the point of the switch — a user
     * who turns it off is asking to skip the ceremony on the commands they give all day, not to
     * arm a silent delete_project on a misheard word.
     */
    private fun needsConfirmation(intents: List<VoiceCommandIntent>): Boolean {
        if (intents.isEmpty()) return false
        if (_uiState.value.confirmBeforeExecute) return true
        return intents.any {
            it.actionType == ActionType.DELETE_PROJECT || it.actionType == ActionType.REMOVE_TASK
        }
    }

    // Confirmation sheet -------------------------------------------------------

    /** Edits one row's task title in place. */
    fun onConfirmationTitleChange(rowId: Int, title: String) {
        updateRow(rowId) { it.copy(intent = it.intent.copy(taskTitle = title)) }
    }

    /** Edits one row's due date in place. Blank clears it back to "no deadline". */
    fun onConfirmationDueDateChange(rowId: Int, dueDate: String) {
        updateRow(rowId) { it.copy(intent = it.intent.copy(dueDate = dueDate.takeIf { d -> d.isNotBlank() })) }
    }

    /** Ticks (or unticks) the separate acknowledgement a destructive row needs. */
    fun onConfirmationAcknowledgeChange(rowId: Int, acknowledged: Boolean) {
        updateRow(rowId) { it.copy(isAcknowledged = acknowledged) }
    }

    private fun updateRow(rowId: Int, transform: (ConfirmableIntent) -> ConfirmableIntent) {
        _uiState.update { state ->
            val confirmation = state.pendingConfirmation ?: return@update state
            // Edits are refused once the batch is running: the rows on screen would no longer be
            // the rows being executed.
            if (confirmation.isExecuting) return@update state
            state.copy(
                pendingConfirmation = confirmation.copy(
                    rows = confirmation.rows.map { if (it.id == rowId) transform(it) else it }
                )
            )
        }
    }

    /** Runs the batch as it now stands on screen, edits included. */
    fun onConfirmationConfirm() {
        val confirmation = _uiState.value.pendingConfirmation ?: return
        if (!confirmation.canExecute) return
        _uiState.update {
            it.copy(
                pendingConfirmation = confirmation.copy(isExecuting = true),
                recordingState = RecordingState.Processing,
                stage = CommandStage.EXECUTING
            )
        }
        updateTimeline { it.atLocalStage(CommandStage.EXECUTING, System.currentTimeMillis()) }
        viewModelScope.launch {
            executeIntents(
                intents = confirmation.intents,
                usage = confirmation.usage,
                agentHandled = confirmation.agentHandled
            )
            _uiState.update { it.copy(pendingConfirmation = null) }
        }
    }

    /**
     * Dismisses the sheet without running anything, putting the original command back in the
     * field so the user can reword it rather than retype it.
     */
    fun onConfirmationDismiss() {
        val confirmation = _uiState.value.pendingConfirmation ?: return
        if (confirmation.isExecuting) return
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                commandText = confirmation.originalText ?: it.commandText
            )
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingProjects = true) }
            projectRepository.getProjects().fold(
                onSuccess = { projects ->
                    _uiState.update { it.copy(projects = projects, isLoadingProjects = false) }
                    loadIssueSummaries(projects)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingProjects = false) }
                    emitEvent(error.toPersianMessage("خطا در بارگذاری پروژه‌ها"))
                }
            )
        }
    }

    // Issue counts -------------------------------------------------------------

    /**
     * Fills in each card's open/closed counts from GitHub, one project at a time.
     *
     * Sequential on purpose: the counts are a nicety on a list the user can already read, and
     * firing one request per project at once would spend the shared GitHub rate limit that repo
     * creation and the model picker also draw on. Each project's answer lands on its own card as
     * it arrives, and a failure is recorded per card rather than raised as a snackbar — a project
     * whose repo was never created is an ordinary state here, not an error the user must dismiss.
     */
    private fun loadIssueSummaries(projects: List<Project>) {
        if (!gitHubRepository.isConfigured || projects.isEmpty()) return
        issueSummaryJob?.cancel()
        issueSummaryJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    issueSummaries = projects.associate { project ->
                        // Keep a previously loaded count visible while it is being refreshed,
                        // so a pull of the list doesn't blink every card back to a spinner.
                        val previous = state.issueSummaries[project.name]
                        project.name to (previous?.copy(isLoading = true) ?: ProjectIssueSummary.LOADING)
                    }
                )
            }
            projects.forEach { project -> loadIssueSummary(project.name) }
        }
    }

    /** Re-reads one project's counts — used when returning from its issues screen. */
    fun refreshIssueSummary(projectName: String) {
        if (!gitHubRepository.isConfigured) return
        viewModelScope.launch { loadIssueSummary(projectName) }
    }

    private suspend fun loadIssueSummary(projectName: String) {
        val issues = gitHubRepository.issuesFor(projectName)
        val summary = issues.fold(
            onSuccess = { list -> ProjectIssueSummary(counts = list.counts) },
            onFailure = { error ->
                ProjectIssueSummary(errorMessage = error.toPersianMessage("خواندن ایشوها ناموفق بود"))
            }
        )
        _uiState.update { state ->
            state.copy(issueSummaries = state.issueSummaries + (projectName to summary))
        }
        // Only for a repo that actually answered: a project whose repo was never created has no
        // issues to learn anything from, and its failure is already on the card.
        issues.getOrNull()?.let { syncClosedIssues(projectName, it.issues) }
    }

    /**
     * Marks tasks done for the issues this project just reported as closed — the return leg of
     * the loop, riding on the read that filled in the counts above.
     *
     * Deliberately silent: this decorates a list the user can already read, so a Supabase write
     * that fails must not raise a snackbar the user has to dismiss on every refresh. It also
     * updates the in-memory project list rather than calling [refreshProjects], which would
     * re-enter [loadIssueSummaries] and sync again in a loop.
     */
    private suspend fun syncClosedIssues(projectName: String, issues: List<RepoIssue>) {
        val project = _uiState.value.projects.firstOrNull { it.name == projectName } ?: return
        val closed = issueTaskSync.closeTasksForClosedIssues(project, issues).getOrNull().orEmpty()
        if (closed.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                projects = state.projects.map { candidate ->
                    if (candidate.id != project.id) candidate
                    else candidate.copy(
                        tasks = candidate.tasks.map { task ->
                            if (task.id in closed) task.copy(isDone = true) else task
                        }
                    )
                }
            )
        }
    }

    private suspend fun executeIntents(
        intents: List<VoiceCommandIntent>,
        usage: TokenUsage?,
        agentHandled: Boolean = _uiState.value.agentHandledByDefault
    ) {
        val result = intentExecutionRepository.executeAll(
            intents = intents,
            agentHandled = agentHandled,
            usage = usage
        )
        _uiState.update { it.copy(recordingState = RecordingState.Idle, stage = CommandStage.NONE) }
        result.fold(
            onSuccess = { outcome ->
                // Show what understanding the command cost, mirroring the footer left on the issue.
                emitEvent(
                    if (usage == null) outcome.message
                    else "${outcome.message}\n${usage.asPersianSummary()}"
                )
                // Only an agent-handled issue has a second half worth watching: one opened without
                // `by-agent` will sit there untouched, and a timeline waiting on it would pulse
                // forever.
                val watched = outcome.issues.filter { it.agentHandled }
                val primary = watched.firstOrNull()
                updateTimeline { timeline ->
                    timeline.handedOff(
                        issue = primary?.let {
                            TrackedIssue(
                                projectName = it.projectName,
                                number = it.issue.number,
                                title = it.issue.title,
                                htmlUrl = it.issue.htmlUrl
                            )
                        },
                        otherIssues = (watched.size - 1).coerceAtLeast(0),
                        now = System.currentTimeMillis()
                    )
                }
                if (primary != null) {
                    _notificationPermissionRequests.trySend(Unit)
                    startTimelinePolling()
                }
                refreshProjects()
            },
            onFailure = { error ->
                updateTimeline { it.failedLocally("اجرای دستور ناموفق بود", System.currentTimeMillis()) }
                emitEvent(error.toPersianMessage("خطایی رخ داد"))
            }
        )
    }

    private fun emitEvent(message: String) {
        _events.trySend(message)
    }

    override fun onCleared() {
        super.onCleared()
        stopTimelinePolling()
        voiceRecorder.cancel()
    }

    private companion object {
        /**
         * How often the tracked issue is re-read while the screen is visible.
         *
         * 20 seconds against a 5000-requests-per-hour token, only while the user is watching, and
         * only until the run finishes: fast enough that a step change looks live, slow enough that
         * a ten-minute agent run costs about thirty reads.
         */
        const val TIMELINE_POLL_MS = 20_000L
    }
}


