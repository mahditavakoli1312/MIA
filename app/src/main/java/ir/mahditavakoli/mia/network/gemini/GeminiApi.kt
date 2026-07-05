package ir.mahditavakoli.mia.network.gemini

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface GeminiApi {
    /**
     * The API key is passed per-call (via the `x-goog-api-key` header) rather than baked into
     * an interceptor, because it's the runtime key from [ir.mahditavakoli.mia.security.SecretStore]
     * — the same key MIA stores as each repo's `GEMINI_API_KEY` Actions secret.
     */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}
