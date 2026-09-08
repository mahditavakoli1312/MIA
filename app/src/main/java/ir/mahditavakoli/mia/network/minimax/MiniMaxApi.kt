package ir.mahditavakoli.mia.network.minimax

import ir.mahditavakoli.mia.network.openrouter.ChatCompletionRequest
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * MiniMax's own chat endpoint, for the `MiniMax-M3` entry that runs on the user's MiniMax
 * account instead of OpenRouter's free tier.
 *
 * It speaks the same OpenAI-compatible dialect as OpenRouter — same request body, same
 * `choices[].message.content`, same `usage` with `completion_tokens_details.reasoning_tokens`
 * — so it reuses the DTOs in `network.openrouter` verbatim rather than duplicating them.
 *
 * **Why `text/chatcompletion_v2` and not `chat/completions`?** MiniMax serves both, but the
 * plain OpenAI path returns the model's thinking *inline*, wrapped in `<think>…</think>` at the
 * top of `content`. MIA never shows a chain of thought and parses `content` as JSON in the
 * extraction step, so that would have to be stripped by hand on every call. `chatcompletion_v2`
 * puts it in a separate `reasoning_content` field, which `ignoreUnknownKeys` drops for free and
 * leaves `content` holding only the answer — the same shape OpenRouter's `reasoning.exclude`
 * produces. (MiniMax accepts the `reasoning` block but ignores it, so it is not sent.)
 *
 * As with [ir.mahditavakoli.mia.network.openrouter.OpenRouterApi], the key is passed per call
 * rather than baked into an interceptor: it is the runtime MiniMax key from
 * [ir.mahditavakoli.mia.security.SecretStore] — the same one MIA pushes to each repo as the
 * `MINIMAX_API_KEY` Actions secret — and the user can change it in Settings at any time.
 */
interface MiniMaxApi {
    @POST("v1/text/chatcompletion_v2")
    suspend fun chatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
