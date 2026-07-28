package ir.mahditavakoli.mia.network.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal request/response models for the Gemini `generateContent` REST endpoint
 * (https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent).
 *
 * A single content holds two parts: the instruction text and the inline audio. The Retrofit
 * client serializes these with `explicitNulls = false`, so the unused nullable field on each
 * part (text on the audio part, inlineData on the text part) is omitted rather than sent as
 * `null` — Gemini rejects a part carrying both.
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiInlineData(
    @SerialName("mime_type") val mimeType: String,
    /** Base64-encoded audio bytes (no line wrapping). */
    val data: String
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double = 0.0,
    // Forces the model to emit a bare JSON object (no ```json fences) matching our intent schema.
    @SerialName("responseMimeType") val responseMimeType: String = "application/json"
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    /** Per-call token accounting. Nullable because error responses omit it entirely. */
    @SerialName("usageMetadata") val usageMetadata: GeminiUsageMetadata? = null
)

@Serializable
data class GeminiCandidate(val content: GeminiResponseContent? = null)

/**
 * What the call cost in tokens. Every field defaults to 0 so a provider that drops one
 * (older models report no `thoughtsTokenCount`) still decodes.
 */
@Serializable
data class GeminiUsageMetadata(
    @SerialName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int = 0,
    /** Thinking tokens on 2.5-series models; billed as output but reported separately. */
    @SerialName("thoughtsTokenCount") val thoughtsTokenCount: Int = 0,
    @SerialName("totalTokenCount") val totalTokenCount: Int = 0
)

@Serializable
data class GeminiResponseContent(val parts: List<GeminiPart> = emptyList())
