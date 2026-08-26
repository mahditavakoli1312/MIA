package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionRequest
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionResponse
import ir.mahditavakoli.mia.network.openrouter.ChatMessage
import ir.mahditavakoli.mia.network.openrouter.Choice
import ir.mahditavakoli.mia.network.openrouter.OpenRouterApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Covers the failover the typed pipeline depends on: a free-tier OpenRouter key that has run
 * out must move the command onto the spare key instead of failing it.
 */
class OxTextIntentClassifierTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val intentJson =
        """[{"action_type":"create_project","project_name":"وبسایت","task_title":null,""" +
            """"task_description":null,"due_date":null}]"""

    /** Answers each call from [script] in order, recording the bearer token it was given. */
    private class FakeOpenRouterApi(private val script: List<Result<String>>) : OpenRouterApi {
        val tokensUsed = mutableListOf<String>()
        private var call = 0

        override suspend fun chatCompletion(
            bearerToken: String,
            request: ChatCompletionRequest
        ): ChatCompletionResponse {
            tokensUsed += bearerToken
            val answer = script[call++].getOrElse { throw it }
            return ChatCompletionResponse(
                choices = listOf(
                    Choice(
                        message = ChatMessage(role = "assistant", content = answer),
                        finishReason = "stop"
                    )
                )
            )
        }
    }

    private fun httpError(code: Int) =
        HttpException(Response.error<Unit>(code, "".toResponseBody(null)))

    private fun classifier(api: OpenRouterApi) = OxTextIntentClassifier(
        api = api,
        json = json,
        apiKeyProvider = { "primary" },
        fallbackApiKeyProvider = { "spare" }
    )

    @Test
    fun `a rate-limited key moves the command onto the spare key`() = runBlocking {
        // Refinement: 429 on the primary, then fine on the spare. Extraction: fine.
        val api = FakeOpenRouterApi(
            listOf(
                Result.failure(httpError(429)),
                Result.success("یک پروژه به اسم وبسایت بساز"),
                Result.success(intentJson)
            )
        )

        val result = classifier(api).classify("یه پروژه وبسایت بساز")

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(ActionType.CREATE_PROJECT, result.getOrThrow().intents.single().actionType)
        // And having learned the primary is spent, the extraction call starts on the spare
        // rather than paying for a second 429.
        assertEquals(listOf("Bearer primary", "Bearer spare", "Bearer spare"), api.tokensUsed)
    }

    @Test
    fun `an exhausted balance (402) also fails over`() = runBlocking {
        val api = FakeOpenRouterApi(
            listOf(
                Result.failure(httpError(402)),
                Result.success("یک پروژه به اسم وبسایت بساز"),
                Result.success(intentJson)
            )
        )

        assertTrue(classifier(api).classify("یه پروژه وبسایت بساز").isSuccess)
        assertEquals("Bearer spare", api.tokensUsed[1])
    }

    @Test
    fun `a non-quota error is reported without burning the spare key`() = runBlocking {
        val api = FakeOpenRouterApi(listOf(Result.failure(httpError(404))))

        val result = classifier(api).classify("یه پروژه وبسایت بساز")

        assertTrue(result.isFailure)
        assertEquals(listOf("Bearer primary"), api.tokensUsed)
    }

    @Test
    fun `both keys spent fails the command rather than retrying forever`() = runBlocking {
        val api = FakeOpenRouterApi(
            listOf(Result.failure(httpError(429)), Result.failure(httpError(429)))
        )

        val result = classifier(api).classify("یه پروژه وبسایت بساز")

        assertTrue(result.isFailure)
        assertEquals(listOf("Bearer primary", "Bearer spare"), api.tokensUsed)
    }

    @Test
    fun `with no fallback configured the single key is tried once`() = runBlocking {
        val api = FakeOpenRouterApi(listOf(Result.failure(httpError(429))))

        val result = OxTextIntentClassifier(
            api = api,
            json = json,
            apiKeyProvider = { "primary" }
        ).classify("یه پروژه وبسایت بساز")

        assertTrue(result.isFailure)
        assertEquals(listOf("Bearer primary"), api.tokensUsed)
    }
}
