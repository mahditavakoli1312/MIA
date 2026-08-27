package ir.mahditavakoli.mia.network.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The default text model for MIA's typed commands: MiniMax M3 on OpenRouter's free tier.
 *
 * It replaced `stealth/ox-alpha`, which OpenRouter withdrew — the risk every stealth model
 * carries, since an anonymous provider can pull one without notice. `minimax/minimax-m3:free`
 * is the like-for-like successor: free on both prompt and completion tokens, a 1M-token
 * context, reasoning, `response_format`, and tool calling (which the CI agent needs). It is
 * deliberately the same model the CI agents run on, so one migration moves the whole stack.
 *
 * Named for the role, not the model, so the next swap is a one-line change here.
 */
const val DEFAULT_TEXT_MODEL = "minimax/minimax-m3:free"

/**
 * The strongest thinking budget MIA asks for. OpenRouter accepts the full effort ladder for
 * this model, and "none" is the one rung that actually stops it thinking (it reports zero
 * reasoning tokens; every other level reports hundreds), so "max" is the ceiling.
 *
 * The model publishes no `supported_efforts` list of its own, which is exactly why MIA sends
 * the level explicitly rather than trusting the endpoint's default: a free endpoint can change
 * that default without notice, and the intent pipeline would quietly get worse with nothing to
 * show for it in the request.
 */
const val MAX_REASONING_EFFORT = "max"

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
 * serializes with `explicitNulls = false`): free endpoints do not all implement structured
 * outputs, and a model that rejects the field would fail the whole call — so the
 * intent step asks for JSON and *also* tolerates a fenced or prose-wrapped answer.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String = DEFAULT_TEXT_MODEL,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    /**
     * Omitted when null, which lets the provider use the model's own maximum — deliberately the
     * default. One command can expand into several `add_task` objects, each carrying a full
     * Persian Markdown brief, and Persian tokenizes expensively: any fixed cap (4096 was one)
     * stops the model mid-JSON, the answer comes back `finish_reason=length`, and the whole
     * command fails *after* both calls were already made. The Gemini voice path is uncapped for
     * the same reason.
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /**
     * Sent on every call, always at full effort — see [Reasoning] and [MAX_REASONING_EFFORT].
     * Null omits the block entirely, which is what a non-reasoning model would need.
     */
    val reasoning: Reasoning? = Reasoning.MAX,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

/**
 * OpenRouter's `reasoning` block: how much the model may think before answering.
 *
 * [exclude] keeps the thinking out of the reply. MIA never shows a chain of thought — both
 * stages only read `message.content` — and at [MAX_REASONING_EFFORT] the trace is by far the
 * largest part of the response, so dropping it server-side saves the phone downloading and
 * parsing tens of thousands of tokens it would throw away. The tokens are still *billed* and
 * still reported under `completion_tokens_details`, so the spend shown to the user is unchanged.
 */
@Serializable
data class Reasoning(
    val effort: String = MAX_REASONING_EFFORT,
    val exclude: Boolean = true
) {
    companion object {
        /** Full power: the model's top effort, with the trace itself left on the server. */
        val MAX = Reasoning()
    }
}

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
