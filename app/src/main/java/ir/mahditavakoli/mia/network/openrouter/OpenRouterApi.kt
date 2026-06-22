package ir.mahditavakoli.mia.network.openrouter

import retrofit2.http.Body
import retrofit2.http.POST

interface OpenRouterApi {
    @POST("api/v1/chat/completions")
    suspend fun classifyIntent(@Body request: ChatCompletionRequest): ChatCompletionResponse
}
