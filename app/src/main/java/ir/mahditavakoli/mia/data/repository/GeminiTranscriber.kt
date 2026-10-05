package ir.mahditavakoli.mia.data.repository

import android.util.Base64
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.network.gemini.GeminiApi
import ir.mahditavakoli.mia.network.gemini.GeminiContent
import ir.mahditavakoli.mia.network.gemini.GeminiInlineData
import ir.mahditavakoli.mia.network.gemini.GeminiPart
import ir.mahditavakoli.mia.network.gemini.GeminiRequest
import retrofit2.HttpException

/** Spoken words, as text, plus what the transcription cost. */
data class Transcription(val text: String, val usage: TokenUsage? = null)

/**
 * Plain speech-to-text through the same Gemini call the intent classifier uses — no intent
 * extraction, no JSON.
 *
 * It exists for the one place in MIA where the user's words are the *content* rather than a
 * command: dictating a brief. [GeminiVoiceIntentClassifier] would answer that audio with an
 * `add_task` intent, which is exactly wrong — a brief is a paragraph to be read by the PO agent,
 * not an instruction to be executed.
 */
class GeminiTranscriber(
    private val api: GeminiApi,
    /** Supplies the runtime Gemini key (Settings override, else BuildConfig default). */
    private val apiKeyProvider: () -> String?,
    private val model: String = "gemini-2.5-flash"
) {
    suspend fun transcribe(wavAudio: ByteArray): Result<Transcription> = runCatching {
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: error("کلید Gemini تنظیم نشده است؛ آن را در تنظیمات وارد کنید")
        require(wavAudio.isNotEmpty()) { "صدایی برای تحلیل ضبط نشد" }

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = PROMPT),
                        GeminiPart(
                            inlineData = GeminiInlineData(
                                mimeType = "audio/wav",
                                data = Base64.encodeToString(wavAudio, Base64.NO_WRAP)
                            )
                        )
                    )
                )
            )
        )

        val response = try {
            api.generateContent(model, apiKey, request)
        } catch (e: HttpException) {
            throw IllegalStateException(describeHttpError(e), e)
        }
        val text = response
            .candidates.firstOrNull()
            ?.content?.parts?.firstOrNull { !it.text.isNullOrBlank() }?.text
            ?.trim()
            ?: error("پاسخ خالی از Gemini دریافت شد")

        Transcription(
            text = text,
            usage = response.usageMetadata?.let { usage ->
                TokenUsage(
                    model = model,
                    promptTokens = usage.promptTokenCount,
                    outputTokens = usage.candidatesTokenCount,
                    reasoningTokens = usage.thoughtsTokenCount,
                    totalTokens = usage.totalTokenCount.takeIf { it > 0 }
                        ?: (usage.promptTokenCount + usage.candidatesTokenCount + usage.thoughtsTokenCount)
                )
            }
        )
    }

    // Same three-way reading of Google's status codes as the classifier: a bad classic key
    // (AIza…) is a 400 API_KEY_INVALID, a bad new-format one (AQ.…) a 401.
    private fun describeHttpError(e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        return when {
            e.code() == 401 || e.code() == 403 || (e.code() == 400 && "API_KEY" in body) ->
                "کلید Gemini نامعتبر یا باطل شده است (HTTP ${e.code()})؛ کلید معتبر را در تنظیمات وارد کنید"
            e.code() == 429 -> "سهمیه Gemini پر شده است؛ کمی بعد دوباره تلاش کنید"
            else -> "خطای Gemini (HTTP ${e.code()})"
        }
    }

    private companion object {
        /**
         * Verbatim and nothing else. Every extra instruction here is an invitation for the model
         * to "help" — summarising a brief, or answering it — and what the user dictated is the
         * one thing this call must return.
         */
        const val PROMPT =
            "Transcribe the speech in this audio verbatim, in the language it was spoken " +
                "(usually Persian). Return ONLY the transcript as plain text: no translation, " +
                "no summary, no commentary, no quotation marks, no markdown. Keep the speaker's " +
                "own wording and sentence order. Use standard Persian punctuation where it is " +
                "clearly implied by the delivery. If the audio contains no intelligible " +
                "speech, return an empty response."
    }
}
