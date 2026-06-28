package ir.mahditavakoli.mia.network.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int = 150
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val message: ChatMessage
)
