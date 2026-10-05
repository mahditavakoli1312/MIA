package ir.mahditavakoli.mia.ui.brief

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.repository.GeminiTranscriber
import ir.mahditavakoli.mia.data.repository.GitHubRepository
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

/** Which field the microphone is currently dictating into. */
enum class BriefField { DESCRIPTION, CRITERIA }

/**
 * What the mic is doing on this screen. [Transcribing] is a separate state from [Recording]
 * because the wait after the tap is the long one, and a spinner that says "listening" through it
 * would tell the user to keep talking to a microphone that is already closed.
 */
sealed interface DictationState {
    data object Idle : DictationState
    data class Recording(val field: BriefField) : DictationState
    data class Transcribing(val field: BriefField) : DictationState
}

data class NewBriefUiState(
    val projectName: String = "",
    val repoName: String = "",
    val title: String = "",
    val description: String = "",
    val successCriteria: String = "",
    val dictation: DictationState = DictationState.Idle,
    val isSubmitting: Boolean = false,
    /** Set when filing failed, shown inline with a retry rather than only as a snackbar. */
    val errorMessage: String? = null
) {
    val isBusy: Boolean get() = isSubmitting || dictation !is DictationState.Idle

    /**
     * A brief needs a title and real prose. The PO agent decomposes what is written down and
     * nothing else, so a one-word description would produce a plan made up out of thin air —
     * which is the failure this whole stage exists to prevent.
     */
    val canSubmit: Boolean
        get() = title.isNotBlank() && description.trim().length >= MIN_DESCRIPTION && !isBusy

    /** Non-null while the description is too short to be a brief, for the field's helper text. */
    val descriptionHint: String?
        get() = if (description.isNotEmpty() && description.trim().length < MIN_DESCRIPTION) {
            "کمی بیشتر توضیح بدهید — دست‌کم $MIN_DESCRIPTION نویسه، تا PO چیزی برای تجزیه داشته باشد."
        } else {
            null
        }

    private companion object {
        const val MIN_DESCRIPTION = 40
    }
}

/**
 * Backs the "نیت جدید" screen: one long-form intent, filed as a single `brief` issue for the PO
 * agent to decompose (see [GitHubRepository.createBrief]).
 *
 * Dictation reuses [VoiceRecorder] — the same recorder the command bar uses — but sends the audio
 * to [GeminiTranscriber] rather than the intent classifier: here the user's words are the content,
 * not a command. The transcript is appended to whichever field the mic was tapped on, so a brief
 * can be dictated in several passes without losing what is already written.
 */
class NewBriefViewModel(application: Application) : AndroidViewModel(application) {

    private val gitHubRepository = NetworkModule.gitHubRepository
    private val voiceRecorder = VoiceRecorder(application)
    private val transcriber = GeminiTranscriber(
        api = NetworkModule.geminiApi,
        apiKeyProvider = { NetworkModule.secretStore.geminiApiKey }
    )

    private val _uiState = MutableStateFlow(NewBriefUiState())
    val uiState: StateFlow<NewBriefUiState> = _uiState.asStateFlow()

    /** Drives the mic button's pulse, exactly as on the main screen. */
    val micAmplitude: StateFlow<Float> = voiceRecorder.amplitude

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Emitted once, after the brief is filed, so the screen can close and the list refresh. */
    private val _filed = Channel<Int>(Channel.BUFFERED)
    val filed = _filed.receiveAsFlow()

    fun load(projectName: String) {
        if (_uiState.value.projectName == projectName) return
        _uiState.update {
            it.copy(projectName = projectName, repoName = GitHubRepository.repoNameFor(projectName))
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value, errorMessage = null) }

    fun onDescriptionChange(value: String) =
        _uiState.update { it.copy(description = value, errorMessage = null) }

    fun onCriteriaChange(value: String) = _uiState.update { it.copy(successCriteria = value) }

    // Dictation ---------------------------------------------------------------

    fun onMicClick(field: BriefField) {
        when (val state = _uiState.value.dictation) {
            is DictationState.Recording ->
                // The second tap on the same field stops it; a tap on the *other* field while
                // recording would otherwise silently discard what was just said.
                if (state.field == field) stopAndTranscribe(field) else Unit
            is DictationState.Transcribing -> Unit
            DictationState.Idle -> startRecording(field)
        }
    }

    private fun startRecording(field: BriefField) {
        if (voiceRecorder.start()) {
            _uiState.update { it.copy(dictation = DictationState.Recording(field)) }
        } else {
            _events.trySend("دسترسی به میکروفون ممکن نشد")
        }
    }

    private fun stopAndTranscribe(field: BriefField) {
        val audio = voiceRecorder.stop()
        if (audio == null) {
            _uiState.update { it.copy(dictation = DictationState.Idle) }
            _events.trySend("صدایی ضبط نشد، دوباره تلاش کنید")
            return
        }
        _uiState.update { it.copy(dictation = DictationState.Transcribing(field)) }
        viewModelScope.launch {
            transcriber.transcribe(audio).fold(
                onSuccess = { transcription ->
                    val text = transcription.text.trim()
                    if (text.isEmpty()) {
                        _uiState.update { it.copy(dictation = DictationState.Idle) }
                        _events.trySend("چیزی از صدا فهمیده نشد")
                        return@fold
                    }
                    _uiState.update { state ->
                        when (field) {
                            BriefField.DESCRIPTION -> state.copy(
                                description = append(state.description, text),
                                dictation = DictationState.Idle,
                                errorMessage = null
                            )

                            BriefField.CRITERIA -> state.copy(
                                successCriteria = append(state.successCriteria, text),
                                dictation = DictationState.Idle
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(dictation = DictationState.Idle) }
                    _events.trySend(error.toPersianMessage("تبدیل صدا به متن ناموفق بود"))
                }
            )
        }
    }

    /** Dictation adds to what is there rather than replacing it — a brief is built up in passes. */
    private fun append(existing: String, addition: String): String =
        if (existing.isBlank()) addition else "${existing.trimEnd()} $addition"

    // Filing ------------------------------------------------------------------

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            gitHubRepository.createBrief(
                projectName = state.projectName,
                title = state.title,
                description = state.description,
                successCriteria = state.successCriteria
            ).fold(
                onSuccess = { issue ->
                    _uiState.update { it.copy(isSubmitting = false) }
                    _events.trySend("نیت #${issue.number} ثبت شد؛ PO آن را تجزیه می‌کند")
                    _filed.trySend(issue.number)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = error.toPersianMessage("ثبت نیت ناموفق بود")
                        )
                    }
                }
            )
        }
    }

    override fun onCleared() {
        // A screen left mid-recording must not keep the microphone open behind the user's back.
        voiceRecorder.cancel()
        super.onCleared()
    }
}
