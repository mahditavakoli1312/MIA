package ir.mahditavakoli.mia.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.data.repository.CommandClassification
import ir.mahditavakoli.mia.data.repository.GeminiVoiceIntentClassifier
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.data.repository.IntentExecutionRepository
import ir.mahditavakoli.mia.data.repository.OxTextIntentClassifier
import ir.mahditavakoli.mia.data.repository.ProjectRepository
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.toPersianMessage
import ir.mahditavakoli.mia.voice.VoiceRecorder
import kotlinx.coroutines.channels.Channel
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
 *  - **Text:** typed Persian -> on-device normalization -> OpenRouter `stealth/ox-alpha` prompt
 *    pre-processing -> intent extraction.
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
    private val textIntentClassifier = OxTextIntentClassifier(
        api = NetworkModule.openRouterApi,
        json = NetworkModule.json,
        apiKeyProvider = { secretStore.agentApiKey }
    )
    private val gitHubRepository = GitHubRepository(
        api = NetworkModule.gitHubApi,
        isConfigured = NetworkModule.isGitHubConfigured,
        bootstrapper = NetworkModule.repoBootstrapper,
        secretStore = secretStore
    )
    private val intentExecutionRepository = IntentExecutionRepository(NetworkModule.supabaseApi, gitHubRepository)
    private val projectRepository = ProjectRepository(NetworkModule.supabaseApi)

    private val _uiState = MutableStateFlow(
        MainUiState(
            agentHandledByDefault = secretStore.agentHandledByDefault,
            geminiApiKey = secretStore.geminiApiKeyOverride,
            openRouterApiKey = secretStore.agentApiKeyOverride
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val micAmplitude: StateFlow<Float> = voiceRecorder.amplitude

    // One-shot user-facing messages (snackbar) — a Channel avoids re-showing the same
    // message on rotation/recomposition the way a plain StateFlow field would.
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

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
     * (`stealth/ox-alpha`) and the per-repo `OPENROUTER_API_KEY` Actions secret the CI agent runs on.
     */
    fun saveOpenRouterApiKey() {
        secretStore.saveAgentApiKey(_uiState.value.openRouterApiKey)
        emitEvent("کلید OpenRouter ذخیره شد")
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
        _uiState.update {
            it.copy(
                commandText = "",
                refinedPrompt = null,
                recordingState = RecordingState.Processing,
                stage = CommandStage.REFINING
            )
        }
        viewModelScope.launch {
            val result = textIntentClassifier.classify(
                rawText = text,
                projects = _uiState.value.projects,
                // Show the refined prompt as soon as the pre-processing call lands, rather than
                // holding it back until the whole command has finished executing.
                onRefined = { refined ->
                    _uiState.update {
                        it.copy(refinedPrompt = refined, stage = CommandStage.UNDERSTANDING)
                    }
                }
            )
            if (result.isFailure) {
                // Restore the command so the user can edit rather than retype it.
                _uiState.update { it.copy(commandText = text) }
            }
            handleClassification(result, failureMessage = "متوجه دستور نشدم")
        }
    }

    /** The one place a classified command — however it arrived — becomes actions. */
    private suspend fun handleClassification(
        result: Result<CommandClassification>,
        failureMessage: String
    ) {
        result.fold(
            onSuccess = { classification ->
                _uiState.update {
                    it.copy(
                        stage = CommandStage.EXECUTING,
                        refinedPrompt = classification.refinedPrompt
                    )
                }
                executeIntents(classification.intents, classification.usage)
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(recordingState = RecordingState.Idle, stage = CommandStage.NONE)
                }
                emitEvent("$failureMessage: ${error.message}")
            }
        )
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingProjects = true) }
            projectRepository.getProjects().fold(
                onSuccess = { projects -> _uiState.update { it.copy(projects = projects, isLoadingProjects = false) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingProjects = false) }
                    emitEvent(error.toPersianMessage("خطا در بارگذاری پروژه‌ها"))
                }
            )
        }
    }

    private suspend fun executeIntents(intents: List<VoiceCommandIntent>, usage: TokenUsage?) {
        val result = intentExecutionRepository.executeAll(
            intents = intents,
            agentHandled = _uiState.value.agentHandledByDefault,
            usage = usage
        )
        _uiState.update { it.copy(recordingState = RecordingState.Idle, stage = CommandStage.NONE) }
        result.fold(
            onSuccess = { message ->
                // Show what understanding the command cost, mirroring the footer left on the issue.
                emitEvent(if (usage == null) message else "$message\n${usage.asPersianSummary()}")
                refreshProjects()
            },
            onFailure = { error -> emitEvent(error.toPersianMessage("خطایی رخ داد")) }
        )
    }

    private fun emitEvent(message: String) {
        _events.trySend(message)
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecorder.cancel()
    }
}
