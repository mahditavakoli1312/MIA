package ir.mahditavakoli.mia.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.data.repository.IntentExecutionRepository
import ir.mahditavakoli.mia.data.repository.ProjectRepository
import ir.mahditavakoli.mia.data.repository.VoiceIntentClassifier
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.voice.SpeechRecognizerManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates the full voice pipeline: mic -> on-device STT -> OpenRouter intent
 * classification -> Supabase execution -> refreshed project list.
 * Plain [AndroidViewModel] — the default Compose `viewModel()` factory wires the
 * Application instance automatically, no custom ViewModelProvider.Factory needed.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val speechRecognizerManager = SpeechRecognizerManager(application)
    private val intentClassifier = VoiceIntentClassifier(NetworkModule.openRouterApi, NetworkModule.json)
    private val intentExecutionRepository = IntentExecutionRepository(NetworkModule.supabaseApi)
    private val projectRepository = ProjectRepository(NetworkModule.supabaseApi)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val micAmplitude: StateFlow<Float> = speechRecognizerManager.amplitude

    // One-shot user-facing messages (snackbar) — a Channel avoids re-showing the same
    // message on rotation/recomposition the way a plain StateFlow field would.
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refreshProjects()
        viewModelScope.launch {
            speechRecognizerManager.state.collect { state -> onSpeechState(state) }
        }
    }

    fun onMicClick() {
        if (_uiState.value.recordingState is RecordingState.Listening) {
            speechRecognizerManager.stopListening()
        } else {
            speechRecognizerManager.startListening()
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingProjects = true) }
            projectRepository.getProjects().fold(
                onSuccess = { projects -> _uiState.update { it.copy(projects = projects, isLoadingProjects = false) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingProjects = false) }
                    emitEvent(error.message ?: "خطا در بارگذاری پروژه‌ها")
                }
            )
        }
    }

    private fun onSpeechState(state: SpeechRecognizerManager.State) {
        when (state) {
            is SpeechRecognizerManager.State.Idle ->
                _uiState.update { it.copy(recordingState = RecordingState.Idle) }
            is SpeechRecognizerManager.State.Listening ->
                _uiState.update { it.copy(recordingState = RecordingState.Listening) }
            is SpeechRecognizerManager.State.Result -> processCandidates(state.candidates)
            is SpeechRecognizerManager.State.Error -> {
                _uiState.update { it.copy(recordingState = RecordingState.Idle) }
                emitEvent(state.message)
            }
        }
    }

    private fun processCandidates(candidates: List<String>) {
        _uiState.update { it.copy(recordingState = RecordingState.Processing) }
        viewModelScope.launch {
            // Pass the currently-loaded projects so the model can resolve spoken names
            // against real data instead of guessing from the raw transcription alone.
            intentClassifier.classify(candidates, _uiState.value.projects).fold(
                onSuccess = { intent -> executeIntent(intent) },
                onFailure = { error ->
                    _uiState.update { it.copy(recordingState = RecordingState.Idle) }
                    emitEvent("متوجه دستور نشدم: ${error.message}")
                }
            )
        }
    }

    private suspend fun executeIntent(intent: VoiceCommandIntent) {
        val result = intentExecutionRepository.execute(intent)
        _uiState.update { it.copy(recordingState = RecordingState.Idle) }
        result.fold(
            onSuccess = { message ->
                emitEvent(message)
                refreshProjects()
            },
            onFailure = { error -> emitEvent(error.message ?: "خطایی رخ داد") }
        )
    }

    private fun emitEvent(message: String) {
        _events.trySend(message)
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizerManager.destroy()
    }
}
