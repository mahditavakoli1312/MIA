package ir.mahditavakoli.mia.network.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The default text model for MIA's typed commands: OpenRouter's free `ox-alpha` stealth model. */
const val OX_ALPHA_MODEL = "stealth/ox-alpha"

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
) {
    companion object {
        fun system(content: String) = ChatMessage(role = "system", content = content)
        fun user(content: String) = ChatMessage(role = "user", content = content)
    }
}

/**
 * An OpenAI-compatible chat-completions request, as OpenRouter accepts it.
 *
 * [responseFormat] is nullable and omitted when null (the Retrofit client for OpenRouter
 * serializes with `explicitNulls = false`): the free stealth models do not all implement
 * structured outputs, and a model that rejects the field would fail the whole call — so the
 * intent step asks for JSON and *also* tolerates a fenced or prose-wrapped answer.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String = OX_ALPHA_MODEL,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

/** `{"type":"json_object"}` — the OpenAI-compatible way to ask for a bare JSON body. */
@Serializable
data class ResponseFormat(val type: String = "json_object")

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
    /** Absent on some providers/errors, so every consumer must tolerate null. */
    val usage: OpenRouterUsage? = null,
    /** OpenRouter reports the model that actually served the request; may differ from the alias. */
    val model: String? = null
)

@Serializable
data class Choice(
    val message: ChatMessage,
    /** "stop", "length", … — "length" means the answer was truncated mid-JSON. */
    @SerialName("finish_reason") val finishReason: String? = null
)

/**
 * OpenRouter's token accounting, normalized into [ir.mahditavakoli.mia.data.model.TokenUsage]
 * by the classifier. Reasoning tokens are nested under `completion_tokens_details` and are
 * already included in `completion_tokens`, so they are reported, not added.
 */
@Serializable
data class OpenRouterUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("completion_tokens_details") val completionDetails: CompletionTokensDetails? = null
) {
    val reasoningTokens: Int get() = completionDetails?.reasoningTokens ?: 0
}

@Serializable
data class CompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0
)
