package ir.mahditavakoli.mia.ui.main

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.IssueCounts
import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.openrouter.DEFAULT_TEXT_MODEL

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Listening : RecordingState
    data object Processing : RecordingState
}

/**
 * One row of the confirmation sheet: an intent the model produced, plus the two things the sheet
 * tracks about it that the intent itself has no room for.
 *
 * [id] is a stable identity for the row across edits — the intent inside it is replaced wholesale
 * on every keystroke, so a list key derived from its contents would make Compose tear down and
 * rebuild the text field the user is typing in.
 */
data class ConfirmableIntent(
    val id: Int,
    val intent: VoiceCommandIntent,
    /**
     * Whether the user has separately ticked this destructive row. Meaningless (and ignored) for
     * everything else — see [IntentConfirmation.canExecute].
     */
    val isAcknowledged: Boolean = false
) {
    /**
     * Actions that destroy data a model may simply have misheard. A wrongly-understood add_task
     * costs one stray issue; a wrongly-understood delete_project takes the project and, by the
     * schema's cascade, every task under it.
     */
    val isDestructive: Boolean
        get() = intent.actionType == ActionType.DELETE_PROJECT ||
            intent.actionType == ActionType.REMOVE_TASK

    /**
     * A create_project row the classifier could not type, which the user has to answer before the
     * batch can run.
     *
     * Blocking rather than defaulting to Android: the choice decides the `AGENTS.md` every agent
     * prompt in that repo will inject, so a silent default is a repo whose agents were told to
     * build the wrong thing — and nothing later in the flow surfaces that. One tap is a much
     * smaller cost than finding out from the first pull request.
     */
    val needsProjectType: Boolean
        get() = intent.actionType == ActionType.CREATE_PROJECT && intent.projectType == null
}

/**
 * The "here is what I understood — shall I?" sheet, held in the ViewModel rather than the
 * composable so an edited-but-not-yet-run batch survives rotation.
 *
 * It stands between classification and [ir.mahditavakoli.mia.data.repository.IntentExecutionRepository],
 * which is the only point where a misheard command is still cheap to fix.
 */
data class IntentConfirmation(
    val rows: List<ConfirmableIntent>,
    /**
     * The command exactly as the user gave it, put back in the field when they choose to reword
     * instead of run. Null for a spoken command — there is no text to give back.
     */
    val originalText: String?,
    /** Carried through untouched so the executed batch still reports what understanding it cost. */
    val usage: TokenUsage? = null,
    /** Frozen at classification time, so toggling the Settings switch mid-sheet can't change it. */
    val agentHandled: Boolean = true,
    /** True from the moment the user confirms until execution lands. */
    val isExecuting: Boolean = false
) {
    /**
     * Every destructive row must be ticked on its own, and every new project must know what it
     * is, before the whole batch can run.
     */
    val canExecute: Boolean
        get() = !isExecuting && rows.isNotEmpty() &&
            rows.all { !it.isDestructive || it.isAcknowledged } &&
            rows.none { it.needsProjectType }

    val intents: List<VoiceCommandIntent> get() = rows.map { it.intent }
}


/**
 * The open/closed issue counts shown on one project card.
 *
 * Loading is per-card rather than global: the project list comes from Supabase and the counts
 * from GitHub, so the cards must be able to render while their counts are still in flight, and
 * one repo failing (deleted, renamed, never created) must not blank out the others.
 */
data class ProjectIssueSummary(
    val isLoading: Boolean = false,
    val counts: IssueCounts? = null,
    /** Set when the read failed — the card shows a quiet retry instead of a wrong "0". */
    val errorMessage: String? = null
) {
    companion object {
        val LOADING = ProjectIssueSummary(isLoading = true)
    }
}

data class MainUiState(
    val projects: List<Project> = emptyList(),
    val isLoadingProjects: Boolean = false,
    val recordingState: RecordingState = RecordingState.Idle,
    /** What the user has typed in the command field but not yet sent. */
    val commandText: String = "",
    val stage: CommandStage = CommandStage.NONE,
    /**
     * The live timeline of the command in flight, or the last one to finish.
     *
     * Non-null well past [stage] returning to NONE: the app's part of a command ends in seconds
     * and the agent's part takes minutes, and this is what keeps the second half visible instead
     * of ending the story at "sent".
     */
    val timeline: CommandTimeline? = null,
    /**
     * The cleaned-up prompt the last typed command was actually understood from, shown back to
     * the user so the pre-processing step is visible rather than a black box.
     */
    val refinedPrompt: String? = null,
    /** New voice-created tasks are handed to the CI agent (labeled "by-agent"). */
    val agentHandledByDefault: Boolean = true,
    /**
     * Whether every understood command is shown for approval before it runs. When off, only
     * destructive batches are — those are never skipped, whatever this says.
     */
    val confirmBeforeExecute: Boolean = true,
    /** What the user last saved as the Gemini API key (empty if none / using build default). */
    val geminiApiKey: String = "",
    /** What the user last saved as the OpenRouter API key (empty if none). */
    val openRouterApiKey: String = "",
    /** The spare OpenRouter key, used only once the primary one is rate limited/out of credit. */
    val openRouterFallbackApiKey: String = "",
    /** What the user last saved as the MiniMax platform key (empty if none). */
    val miniMaxApiKey: String = "",
    /** Which model the app's own typed commands run on — a device preference, not a repo one. */
    val textModelId: String = DEFAULT_TEXT_MODEL,
    /** False without a GitHub token — the per-project model screen has nothing to talk to. */
    val isGitHubConfigured: Boolean = false,
    /** Non-null while a classified command is waiting for the user's approval. */
    val pendingConfirmation: IntentConfirmation? = null,
    /** Issue counts per project name; missing means "not requested yet". */
    val issueSummaries: Map<String, ProjectIssueSummary> = emptyMap()
) {
    /** True while any command is in flight — both front doors stay disabled until it lands. */
    val isBusy: Boolean get() = recordingState is RecordingState.Processing

    /** The send button is only meaningful with text to send and nothing already running. */
    val canSendText: Boolean get() = commandText.isNotBlank() && !isBusy &&
        recordingState !is RecordingState.Listening
}
