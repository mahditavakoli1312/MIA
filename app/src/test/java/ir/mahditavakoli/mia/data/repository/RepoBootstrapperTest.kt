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

    // Stands in for the seven real bundled files; the bootstrapper commits each verbatim.
    private val files = listOf(
        BootstrapFile(".github/workflows/agent-issue-worker.yml", "name: Agent Issue Worker\n"),
        BootstrapFile(".github/workflows/ai-role-review.yml", "name: AI Role Review\n"),
        BootstrapFile(".github/workflows/add-to-project.yml", "name: Add issues to project\n"),
        BootstrapFile(".github/workflows/ci.yml", "name: CI\n"),
        BootstrapFile(".github/scripts/ai-role-review.js", "// role review\n"),
        BootstrapFile(".github/scripts/token-usage.js", "// token usage\n")
    )

    // Deterministic stand-ins for the Android-native crypto so tests run on the JVM.
    private val base64 = Base64Encoder { Base64.getEncoder().encodeToString(it) }
    private val encryptor = SecretEncryptor { plaintext, publicKey -> "sealed($plaintext|$publicKey)" }

    private fun bootstrapper(api: FakeGitHubApi, template: String = "") =
        RepoBootstrapper(api, base64, encryptor, files, templateRepo = template)

    @Test
    fun `plain creation uploads all team files, creates the queue labels, sets secret`() = runBlocking {
        val api = FakeGitHubApi()

        val result = bootstrapper(api).bootstrap(
            owner = "octocat",
            name = "test-repo",
            description = "desc",
            private = true,
            agentApiKey = "agent-secret"
        )

        assertTrue("no warnings expected: ${result.warnings}", result.warnings.isEmpty())
        assertNotNull("repo should be created via plain create", api.createRepoBody)
        assertNull("template route must not be used", api.generateBody)

        // Every bundled file uploaded to its path, base64-encoded, and nothing extra.
        val uploaded = api.putContents.associate { (path, body) -> path to body.content }
        assertEquals(files.map { it.repoPath }.toSet(), uploaded.keys)
        for (file in files) {
            assertEquals(base64.encode(file.content.toByteArray()), uploaded[file.repoPath])
        }

        // Every queue label, with the required colors.
        assertEquals(
            RepoBootstrapper.LABELS.toSet(),
            api.createdLabels.map { it.name to it.color }.toSet()
        )

        // Secret sealed with the repo public key and stored under the right name.
        assertEquals(RepoBootstrapper.SECRET_NAME, api.putSecretName)
        assertEquals("sealed(agent-secret|${api.publicKey.key})", api.putSecretBody?.encryptedValue)
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
            agentApiKey = "agent-secret"
        )

        assertTrue(result.warnings.isEmpty())
        assertEquals("mia-org" to "mia-template", api.generateTemplate)
        assertNull("plain createRepo must not be called in template mode", api.createRepoBody)
        assertTrue("template already carries the workflow", api.putContents.isEmpty())
        // Labels + secret still applied.
        assertEquals(RepoBootstrapper.LABELS.size, api.createdLabels.size)
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
    fun `missing agent key skips secret and warns`() = runBlocking {
        val api = FakeGitHubApi()

        val result = bootstrapper(api).bootstrap("octocat", "r", null, true, agentApiKey = null)

        assertNull("secret must not be set without a key", api.putSecretBody)
        assertTrue(result.warnings.any { it.contains("OPENROUTER_API_KEY") })
    }

    @Test
    fun `fallback key is stored as its own secret alongside the primary`() = runBlocking {
        val api = FakeGitHubApi()

        val result = bootstrapper(api).bootstrap(
            owner = "octocat",
            name = "r",
            description = null,
            private = true,
            agentApiKey = "primary",
            agentFallbackApiKey = "spare"
        )

        assertTrue("no warnings expected: ${result.warnings}", result.warnings.isEmpty())
        val stored = api.putSecrets.associate { (name, body) -> name to body.encryptedValue }
        assertEquals(
            setOf(RepoBootstrapper.SECRET_NAME, RepoBootstrapper.FALLBACK_SECRET_NAME),
            stored.keys
        )
        assertEquals("sealed(primary|${api.publicKey.key})", stored[RepoBootstrapper.SECRET_NAME])
        assertEquals("sealed(spare|${api.publicKey.key})", stored[RepoBootstrapper.FALLBACK_SECRET_NAME])
    }

    @Test
    fun `a fallback identical to the primary is not stored twice`() = runBlocking {
        val api = FakeGitHubApi()

        bootstrapper(api).bootstrap("octocat", "r", null, true, "same-key", "same-key")

        assertEquals(listOf(RepoBootstrapper.SECRET_NAME), api.putSecrets.map { it.first })
    }

    @Test
    fun `a failed fallback secret warns but leaves the primary in place`() = runBlocking {
        val api = FakeGitHubApi().apply {
            putSecretResponse = { name ->
                if (name == RepoBootstrapper.FALLBACK_SECRET_NAME) FakeGitHubApi.error(500)
                else Response.success(Unit)
            }
        }

        val result = bootstrapper(api).bootstrap("octocat", "r", null, true, "primary", "spare")

        assertTrue(
            "the fallback failure must be named: ${result.warnings}",
            result.warnings.any { it.contains(RepoBootstrapper.FALLBACK_SECRET_NAME) }
        )
        // The primary still went in — a broken spare is not worth losing the working key over.
        assertTrue(api.putSecrets.any { it.first == RepoBootstrapper.SECRET_NAME })
    }

    @Test
    fun `file upload failure is reported as a warning but does not throw`() = runBlocking {
        val api = FakeGitHubApi().apply { putContentResponse = { FakeGitHubApi.error(500) } }

        val result = bootstrapper(api).bootstrap("octocat", "r", null, true, "k")

        // One warning per failed file, each naming its path — but the run still finishes.
        assertEquals(files.size, result.warnings.count { it.contains("upload of") })
        assertTrue(result.warnings.any { it.contains("agent-issue-worker.yml") })
        // Later steps still ran.
        assertEquals(RepoBootstrapper.LABELS.size, api.createdLabels.size)
        assertNotNull(api.putSecretBody)
    }
}
