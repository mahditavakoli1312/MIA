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
    val recordingState: RecordingState = RecordingState.Idle
)
