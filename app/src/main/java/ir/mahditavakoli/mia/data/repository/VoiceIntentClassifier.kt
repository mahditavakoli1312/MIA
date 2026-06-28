package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionRequest
import ir.mahditavakoli.mia.network.openrouter.ChatMessage
import ir.mahditavakoli.mia.network.openrouter.IntentClassifierPrompt
import ir.mahditavakoli.mia.network.openrouter.OpenRouterApi
import kotlinx.serialization.json.Json

/**
 * Sends the Persian speech-recognition candidates (plus the user's current projects/tasks
 * as grounding context) to OpenRouter and parses the model's strict-JSON reply.
 */
class VoiceIntentClassifier(
    private val api: OpenRouterApi,
    private val json: Json,
    private val model: String = "gpt-4o-mini"
) {
    suspend fun classify(
        candidates: List<String>,
        projects: List<Project> = emptyList()
    ): Result<VoiceCommandIntent> = runCatching {
        require(candidates.isNotEmpty()) { "هیچ متنی برای تحلیل وجود ندارد" }
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = IntentClassifierPrompt.build(projects = projects)),
                ChatMessage(role = "user", content = buildUserMessage(candidates))
            )
        )
        val rawContent = api.classifyIntent(request).choices.firstOrNull()?.message?.content
            ?: error("پاسخ خالی از مدل دریافت شد")
        json.decodeFromString<VoiceCommandIntent>(sanitize(rawContent))
    }

    // A single candidate is sent verbatim; multiple candidates are presented as a ranked list
    // so the model can pick the interpretation that best matches the user's existing data.
    private fun buildUserMessage(candidates: List<String>): String {
        if (candidates.size == 1) return candidates.first()
        return buildString {
            appendLine("Candidate transcriptions (most likely first):")
            candidates.forEachIndexed { index, candidate -> appendLine("${index + 1}. $candidate") }
        }.trim()
    }

    // Defensive: some models still wrap JSON in ```json fences despite the system prompt forbidding it.
    private fun sanitize(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
