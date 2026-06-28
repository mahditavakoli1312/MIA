package ir.mahditavakoli.mia.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps the platform [SpeechRecognizer] for Persian (fa-IR) dictation.
 * Must be driven from the main thread (SpeechRecognizer is not thread-safe).
 */
class SpeechRecognizerManager(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        // N-best transcriptions, most-likely first. We keep all of them (not just the top
        // hit) so the downstream LLM can pick the candidate that actually makes sense given
        // the user's existing projects — Persian STT often mishears the right word but still
        // includes it lower in this list.
        data class Result(val candidates: List<String>) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    // Normalized 0f..1f mic level, driven by onRmsChanged — feed this into the FAB's neon pulse animation.
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    private var recognizer: SpeechRecognizer? = null

    // Some devices/recognizer implementations never call back at all (e.g. no network,
    // no bound recognition service) — without a watchdog the UI would stay stuck on
    // Listening forever with no way out.
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Watchdog fired — no RecognitionListener callback arrived in time, forcing Error state")
        releaseRecognizer()
        _state.value = State.Error("پاسخی از تشخیص گفتار دریافت نشد، دوباره تلاش کنید")
    }

    fun startListening() {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "startListening() — isRecognitionAvailable=$available")
        if (!available) {
            Log.e(TAG, "No speech recognition service is bound on this device/emulator")
            _state.value = State.Error("تشخیص گفتار روی این دستگاه در دسترس نیست")
            return
        }
        releaseRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            // EXTRA_PREFER_OFFLINE intentionally omitted: when no offline model exists for the
            // requested locale, several recognizer implementations silently drop the whole
            // request instead of falling back online (no callback at all, not even onError).
            // Plain SpeechRecognizer is free either way — "no extra cost" just means no paid
            // third-party STT API, which this still satisfies whether it resolves on-device or online.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Ask for several hypotheses, not just the single best one. The extra candidates
            // are forwarded to the intent classifier so it can disambiguate mis-hearings
            // against the real project/task names instead of failing on a wrong top guess.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        }
        _state.value = State.Listening
        Log.d(TAG, "Calling SpeechRecognizer.startListening() with language=fa-IR")
        recognizer?.startListening(intent)
        timeoutHandler.postDelayed(timeoutRunnable, LISTENING_TIMEOUT_MS)
    }

    fun stopListening() {
        Log.d(TAG, "stopListening() called by user")
        recognizer?.stopListening()
        // Safety net: if the recognizer never calls back after a manual stop either,
        // don't leave the UI stuck on Listening.
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, STOP_GRACE_MS)
    }

    fun destroy() {
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            _amplitude.value = (rmsdB / 10f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            Log.e(TAG, "onError code=$error (${errorName(error)})")
            timeoutHandler.removeCallbacks(timeoutRunnable)
            _state.value = State.Error(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
            Log.d(TAG, "onResults candidates=$candidates")
            timeoutHandler.removeCallbacks(timeoutRunnable)
            _state.value = if (candidates.isNotEmpty()) {
                State.Result(candidates)
            } else {
                State.Error("متوجه نشدم، دوباره تلاش کنید")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "متوجه نشدم، دوباره تلاش کنید"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "اجازه دسترسی به میکروفون داده نشده است"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "خطا در اتصال شبکه"
        else -> "خطای ناشناخته در تشخیص گفتار"
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN($error)"
    }

    private companion object {
        const val TAG = "MIA_Speech"
        const val LISTENING_TIMEOUT_MS = 12_000L
        const val STOP_GRACE_MS = 4_000L
        const val MAX_RESULTS = 5
    }
}
