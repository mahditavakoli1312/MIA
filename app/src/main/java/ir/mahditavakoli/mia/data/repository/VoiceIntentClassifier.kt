package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionRequest
import ir.mahditavakoli.mia.network.openrouter.ChatMessage
import ir.mahditavakoli.mia.network.openrouter.IntentClassifierPrompt
import ir.mahditavakoli.mia.network.openrouter.OpenRouterApi
import kotlinx.serialization.json.Json

/** Sends a Persian transcript to OpenRouter and parses the model's strict-JSON reply. */
class VoiceIntentClassifier(
    private val api: OpenRouterApi,
    private val json: Json,
    private val model: String = "anthropic/claude-3-haiku"
) {
    suspend fun classify(transcript: String): Result<VoiceCommandIntent> = runCatching {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = IntentClassifierPrompt.build()),
                ChatMessage(role = "user", content = transcript)
            )
        )
        val rawContent = api.classifyIntent(request).choices.firstOrNull()?.message?.content
            ?: error("پاسخ خالی از مدل دریافت شد")
        json.decodeFromString<VoiceCommandIntent>(sanitize(rawContent))
    }

    // Defensive: some models still wrap JSON in ```json fences despite the system prompt forbidding it.
    private fun sanitize(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
