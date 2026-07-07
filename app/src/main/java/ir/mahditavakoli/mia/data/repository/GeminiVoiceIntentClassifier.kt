package ir.mahditavakoli.mia.data.repository

import android.util.Base64
import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.gemini.GeminiApi
import ir.mahditavakoli.mia.network.gemini.GeminiContent
import ir.mahditavakoli.mia.network.gemini.GeminiInlineData
import ir.mahditavakoli.mia.network.gemini.GeminiIntentPrompt
import ir.mahditavakoli.mia.network.gemini.GeminiPart
import ir.mahditavakoli.mia.network.gemini.GeminiRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import retrofit2.HttpException

/**
 * Sends recorded WAV audio (plus the user's projects/tasks as grounding context) straight to
 * Gemini, which transcribes the Persian speech and returns the app's strict intent JSON in one
 * multimodal call — replacing the old on-device STT + text-classifier pipeline.
 *
 * The response is an ARRAY of intents: a single spoken command may describe several distinct
 * pieces of work, which the model splits into multiple focused issues (see [GeminiIntentPrompt]).
 */
class GeminiVoiceIntentClassifier(
    private val api: GeminiApi,
    private val json: Json,
    /** Supplies the runtime Gemini key (Settings override, else BuildConfig default). */
    private val apiKeyProvider: () -> String?,
    private val model: String = "gemini-2.5-flash"
) {
    suspend fun classify(
        wavAudio: ByteArray,
        projects: List<Project> = emptyList()
    ): Result<List<VoiceCommandIntent>> = runCatching {
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: error("کلید Gemini تنظیم نشده است؛ آن را در تنظیمات وارد کنید")
        require(wavAudio.isNotEmpty()) { "صدایی برای تحلیل ضبط نشد" }

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = GeminiIntentPrompt.build(projects = projects)),
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
        val rawContent = response
            .candidates.firstOrNull()
            ?.content?.parts?.firstOrNull { !it.text.isNullOrBlank() }?.text
            ?: error("پاسخ خالی از Gemini دریافت شد")

        parseIntents(sanitize(rawContent))
            .ifEmpty { error("هیچ دستوری از صدا استخراج نشد") }
    }

    // Google reports a bad classic key (AIza…) as 400 API_KEY_INVALID but a bad new-format
    // key (AQ.…) as 401 UNAUTHENTICATED, so all three of 400/401/403 can mean "bad key".
    private fun describeHttpError(e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        return when {
            e.code() == 401 || e.code() == 403 || (e.code() == 400 && "API_KEY" in body) ->
                "کلید Gemini نامعتبر یا باطل شده است (HTTP ${e.code()})؛ کلید معتبر را در تنظیمات وارد کنید"
            e.code() == 429 ->
                "سهمیه Gemini پر شده است؛ کمی بعد دوباره تلاش کنید"
            else -> "خطای Gemini (HTTP ${e.code()})"
        }
    }

    // The prompt asks for a JSON array, but tolerate a bare object too so a slightly
    // off-spec response still yields a single-intent list instead of failing outright.
    private fun parseIntents(cleaned: String): List<VoiceCommandIntent> {
        val element = json.parseToJsonElement(cleaned)
        return if (element is JsonArray) {
            json.decodeFromJsonElement(ListSerializer(VoiceCommandIntent.serializer()), element)
        } else {
            listOf(json.decodeFromJsonElement(VoiceCommandIntent.serializer(), element))
        }
    }

    // Defensive: even with responseMimeType=application/json, strip any stray code fences.
    private fun sanitize(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
