package ir.mahditavakoli.mia.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures microphone audio as 16 kHz mono PCM and returns it wrapped in a WAV container.
 *
 * This replaces the platform [SpeechRecognizer] path: instead of doing speech-to-text on
 * device and sending *text* to a classifier, MIA now sends the raw audio straight to Gemini,
 * which transcribes and extracts the intent in one multimodal call. 16 kHz mono is ideal for
 * speech and keeps the base64 payload small.
 *
 * Recording runs on a dedicated thread ([AudioRecord.read] blocks); [amplitude] is updated
 * from each buffer so the mic FAB can pulse, exactly like the old RMS callback did.
 */
class VoiceRecorder(private val context: Context) {

    // Normalized 0f..1f mic level for the FAB's neon pulse animation.
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val pcmBuffer = ByteArrayOutputStream()

    /** True if the mic could be opened and capture started. */
    @SuppressLint("MissingPermission") // The UI gates the mic behind the RECORD_AUDIO grant; re-checked below.
    fun start(): Boolean {
        if (recording) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            return false
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "getMinBufferSize failed ($minBuffer)")
            return false
        }
        val bufferSize = minBuffer * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return false
        }
        audioRecord = record
        pcmBuffer.reset()
        recording = true
        record.startRecording()
        recordThread = Thread { readLoop(record, bufferSize) }.apply { start() }
        return true
    }

    private fun readLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (recording) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                pcmBuffer.write(buffer, 0, read)
                _amplitude.value = rms(buffer, read)
            }
        }
    }

    /**
     * Stops capture and returns the recording as a WAV byte array, or null if nothing usable
     * was captured (e.g. the user tapped stop instantly).
     */
    fun stop(): ByteArray? {
        if (!recording) return null
        recording = false
        recordThread?.join(1_000)
        recordThread = null
        audioRecord?.apply { runCatching { stop() }; release() }
        audioRecord = null
        _amplitude.value = 0f
        val pcm = pcmBuffer.toByteArray()
        pcmBuffer.reset()
        // Guard against sub-noise clips too short to hold any speech.
        if (pcm.size < MIN_PCM_BYTES) return null
        return wrapWav(pcm)
    }

    /** Abort without producing audio (used on teardown). */
    fun cancel() {
        recording = false
        recordThread?.join(1_000)
        recordThread = null
        audioRecord?.apply { runCatching { stop() }; release() }
        audioRecord = null
        pcmBuffer.reset()
        _amplitude.value = 0f
    }

    // Root-mean-square of the 16-bit samples, scaled into a lively 0..1 range for the pulse.
    private fun rms(bytes: ByteArray, length: Int): Float {
        val shorts = length / 2
        if (shorts == 0) return 0f
        var sumSquares = 0.0
        val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        repeat(shorts) {
            val sample = bb.short.toInt()
            sumSquares += (sample * sample).toDouble()
        }
        val rms = Math.sqrt(sumSquares / shorts)
        return (rms / 8_000.0).coerceIn(0.0, 1.0).toFloat()
    }

    // Prepends the standard 44-byte PCM WAV header so Gemini receives a self-describing audio/wav.
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                 // PCM subchunk size
        header.putShort(1)                // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        return header.array() + pcm
    }

    private companion object {
        const val TAG = "MIA_VoiceRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // ~0.25s of 16 kHz/16-bit mono audio; anything shorter can't contain a command.
        const val MIN_PCM_BYTES = SAMPLE_RATE * 2 / 4
    }
}
