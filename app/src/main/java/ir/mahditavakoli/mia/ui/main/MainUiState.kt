package ir.mahditavakoli.mia.ui.main

import ir.mahditavakoli.mia.data.model.IssueCounts
import ir.mahditavakoli.mia.data.model.Project

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Listening : RecordingState
    data object Processing : RecordingState
}

/**
 * Which stage of a command is running, so the status banner can say something truthful instead
 * of a single opaque "processing" for what is really three different waits.
 */
enum class CommandStage {
    /** Nothing in flight. */
    NONE,

    /** Typed text is being rewritten into an explicit prompt (OpenRouter pre-processing). */
    REFINING,

    /** The prompt (or the recorded audio) is being turned into intent JSON. */
    UNDERSTANDING,

    /** Intents are being executed against Supabase/GitHub. */
    EXECUTING
}

/**
 * The "which model does this project's AI team run on" sheet, open for exactly one project.
 *
 * [currentModel] is read from the repo when the sheet opens rather than remembered: the
 * workflow files on GitHub are the source of truth and can change without MIA's involvement.
 * Null once loading finishes means the repo names no model at all — an older bootstrap.
 */
data class AgentModelDialogState(
    val projectName: String,
    val repoName: String,
    val currentModel: String? = null,
    val isLoadingCurrent: Boolean = true,
    /** True from the moment a model is picked until GitHub has been written. */
    val isApplying: Boolean = false
)

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
     * The cleaned-up prompt the last typed command was actually understood from, shown back to
     * the user so the pre-processing step is visible rather than a black box.
     */
    val refinedPrompt: String? = null,
    /** New voice-created tasks are handed to the CI agent (labeled "by-agent"). */
    val agentHandledByDefault: Boolean = true,
    /** What the user last saved as the Gemini API key (empty if none / using build default). */
    val geminiApiKey: String = "",
    /** What the user last saved as the OpenRouter API key (empty if none). */
    val openRouterApiKey: String = "",
    /** The spare OpenRouter key, used only once the primary one is rate limited/out of credit. */
    val openRouterFallbackApiKey: String = "",
    /** False without a GitHub token — the per-project model picker has nothing to talk to. */
    val isGitHubConfigured: Boolean = false,
    /** Non-null while the model picker is open for one project. */
    val agentModelDialog: AgentModelDialogState? = null,
    /** Issue counts per project name; missing means "not requested yet". */
    val issueSummaries: Map<String, ProjectIssueSummary> = emptyMap()
) {
    /** True while any command is in flight — both front doors stay disabled until it lands. */
    val isBusy: Boolean get() = recordingState is RecordingState.Processing

    /** The send button is only meaningful with text to send and nothing already running. */
    val canSendText: Boolean get() = commandText.isNotBlank() && !isBusy &&
        recordingState !is RecordingState.Listening
}
