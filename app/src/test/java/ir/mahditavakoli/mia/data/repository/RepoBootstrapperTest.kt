package ir.mahditavakoli.mia.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64

class RepoBootstrapperTest {

    private val workflowYaml = "name: Agent Issue Worker\n"

    // Deterministic stand-ins for the Android-native crypto so tests run on the JVM.
    private val base64 = Base64Encoder { Base64.getEncoder().encodeToString(it) }
    private val encryptor = SecretEncryptor { plaintext, publicKey -> "sealed($plaintext|$publicKey)" }

    private fun bootstrapper(api: FakeGitHubApi, template: String = "") =
        RepoBootstrapper(api, base64, encryptor, workflowYaml, templateRepo = template)

    @Test
    fun `plain creation uploads workflow, creates both labels, sets secret`() = runBlocking {
        val api = FakeGitHubApi()

        val result = bootstrapper(api).bootstrap(
            owner = "octocat",
            name = "test-repo",
            description = "desc",
            private = true,
            geminiApiKey = "gemini-secret"
        )

        assertTrue("no warnings expected: ${result.warnings}", result.warnings.isEmpty())
        assertNotNull("repo should be created via plain create", api.createRepoBody)
        assertNull("template route must not be used", api.generateBody)

        // Workflow uploaded to the right path, base64-encoded.
        val (path, content) = api.putContents.single()
        assertEquals(RepoBootstrapper.WORKFLOW_PATH, path)
        assertEquals(base64.encode(workflowYaml.toByteArray()), content.content)

        // Both labels with the required colors.
        assertEquals(
            RepoBootstrapper.LABELS.toSet(),
            api.createdLabels.map { it.name to it.color }.toSet()
        )

        // Secret sealed with the repo public key and stored under the right name.
        assertEquals(RepoBootstrapper.SECRET_NAME, api.putSecretName)
        assertEquals("sealed(gemini-secret|${api.publicKey.key})", api.putSecretBody?.encryptedValue)
        assertEquals(api.publicKey.keyId, api.putSecretBody?.keyId)
    }

    @Test
    fun `template route generates repo and skips workflow upload`() = runBlocking {
        val api = FakeGitHubApi()

        val result = bootstrapper(api, template = "mia-org/mia-template").bootstrap(
            owner = "octocat",
            name = "test-repo",
            description = null,
            private = true,
            geminiApiKey = "gemini-secret"
        )

        assertTrue(result.warnings.isEmpty())
        assertEquals("mia-org" to "mia-template", api.generateTemplate)
        assertNull("plain createRepo must not be called in template mode", api.createRepoBody)
        assertTrue("template already carries the workflow", api.putContents.isEmpty())
        // Labels + secret still applied.
        assertEquals(2, api.createdLabels.size)
        assertNotNull(api.putSecretBody)
    }

    @Test
    fun `existing label (HTTP 422) is tolerated without a warning`() = runBlocking {
        val api = FakeGitHubApi().apply {
            labelResponse = { body ->
                if (body.name == "by-agent") FakeGitHubApi.error(422) else Response.success(Unit)
            }
        }

        val result = bootstrapper(api).bootstrap("octocat", "r", null, true, "k")

        assertTrue("422 already-exists must not warn: ${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun `missing gemini key skips secret and warns`() = runBlocking {
        val api = FakeGitHubApi()

        val result = bootstrapper(api).bootstrap("octocat", "r", null, true, geminiApiKey = null)

        assertNull("secret must not be set without a key", api.putSecretBody)
        assertTrue(result.warnings.any { it.contains("GEMINI_API_KEY") })
    }

    @Test
    fun `workflow upload failure is reported as a warning but does not throw`() = runBlocking {
        val api = FakeGitHubApi().apply { putContentResponse = { FakeGitHubApi.error(500) } }

        val result = bootstrapper(api).bootstrap("octocat", "r", null, true, "k")

        assertTrue(result.warnings.any { it.contains("workflow") })
        // Later steps still ran.
        assertEquals(2, api.createdLabels.size)
        assertNotNull(api.putSecretBody)
    }
}
