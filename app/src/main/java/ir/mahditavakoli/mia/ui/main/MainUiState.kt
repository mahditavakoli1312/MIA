package ir.mahditavakoli.mia.ui.main

import ir.mahditavakoli.mia.data.model.Project

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Listening : RecordingState
    data object Processing : RecordingState
}

data class MainUiState(
    val projects: List<Project> = emptyList(),
    val isLoadingProjects: Boolean = false,
    val recordingState: RecordingState = RecordingState.Idle,
    /** New voice-created tasks are handed to the Gemini agent (labeled "by-agent"). */
    val agentHandledByDefault: Boolean = true,
    /** What the user last saved as the Gemini API key (empty if none / using build default). */
    val geminiApiKey: String = ""
)
