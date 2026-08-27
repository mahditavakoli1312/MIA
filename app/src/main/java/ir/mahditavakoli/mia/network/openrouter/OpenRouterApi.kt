package ir.mahditavakoli.mia.network.openrouter

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenRouterApi {
    /**
     * OpenAI-compatible chat completions, used for MIA's typed-command pipeline (prompt
     * refinement, then intent extraction) on the free `minimax/minimax-m3:free` model.
     *
     * Like [ir.mahditavakoli.mia.network.gemini.GeminiApi], the key is passed per call rather
     * than baked into an interceptor: it is the runtime OpenRouter key from
     * [ir.mahditavakoli.mia.security.SecretStore] — the same one MIA pushes to each repo as the
     * `OPENROUTER_API_KEY` Actions secret — and the user can change it in Settings at any time.
     */
    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
