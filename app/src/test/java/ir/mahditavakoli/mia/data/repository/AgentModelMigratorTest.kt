package ir.mahditavakoli.mia.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The migrator edits files that live in *other people's repos*, so these tests care about two
 * things above all: that the model id lands in the right form for each reader (OpenCode needs
 * `openrouter/`, the PO/QC script must not have it), and that nothing else in the file moves.
 */
class AgentModelMigratorTest {

    private val jvmEncoder = Base64Encoder { bytes -> Base64.getEncoder().encodeToString(bytes) }
    private val jvmDecoder = Base64Decoder { value -> Base64.getDecoder().decode(value) }

    private val workerYml = """
        jobs:
          tec:
            steps:
              - name: Run OpenCode CLI
                env:
                  AGENT_MODEL: ${'$'}{{ vars.AGENT_MODEL || 'openrouter/stealth/ox-alpha' }}
              - name: Report token spend
                env:
                  AGENT_MODEL: ${'$'}{{ vars.AGENT_MODEL || 'openrouter/stealth/ox-alpha' }}
    """.trimIndent()

    private val roleReviewJs = """
        const model = process.env.AGENT_MODEL || "stealth/ox-alpha";
        // A comment naming stealth/ox-alpha that must survive untouched.
    """.trimIndent()

    private fun migrator(api: FakeGitHubApi) =
        AgentModelMigrator(api = api, base64 = jvmEncoder, base64Decoder = jvmDecoder)

    private fun FakeGitHubApi.written(path: String): String =
        String(Base64.getDecoder().decode(putContents.first { it.first == path }.second.content))

    @Test
    fun `each file keeps the prefix form its own reader needs`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = workerYml
            contents[".github/scripts/ai-role-review.js"] = roleReviewJs
        }

        val outcome = migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        // OpenCode splits "provider/model" on the first slash, so TEC's copy keeps openrouter/.
        assertTrue(
            api.written(".github/workflows/agent-issue-worker.yml")
                .contains("vars.AGENT_MODEL || 'openrouter/minimax/minimax-m3:free' }}")
        )
        // The PO/QC script talks to the OpenRouter API directly and must send the bare id.
        assertTrue(
            api.written(".github/scripts/ai-role-review.js")
                .contains("process.env.AGENT_MODEL || \"minimax/minimax-m3:free\";")
        )
        assertEquals(2, outcome.updated.size)
        assertTrue(outcome.didChange)
    }

    @Test
    fun `every AGENT_MODEL default in a file is repointed, not just the first`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = workerYml
        }

        migrator(api).setModel("octocat", "repo", "z-ai/glm-5.2:free")

        // The worker names the model twice (the run step and the spend report); leaving the
        // second one behind would report a different model than the one that did the work.
        val after = api.written(".github/workflows/agent-issue-worker.yml")
        assertEquals(2, Regex("openrouter/z-ai/glm-5\\.2:free").findAll(after).count())
        assertTrue("ox-alpha" !in after)
    }

    @Test
    fun `nothing outside the model default is touched`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] = roleReviewJs
        }

        migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        // A user may have edited the role prompts, and prose about the old model is still
        // accurate history — only the default itself may move.
        val after = api.written(".github/scripts/ai-role-review.js")
        assertTrue(after.contains("// A comment naming stealth/ox-alpha that must survive untouched."))
    }

    @Test
    fun `the blob sha is echoed back so a concurrent edit is not clobbered`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = workerYml
        }

        migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        val body = api.putContents.single().second
        assertEquals("sha-" + ".github/workflows/agent-issue-worker.yml".hashCode(), body.sha)
    }

    @Test
    fun `a repo already on the model is reported, not rewritten`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] =
                "const model = process.env.AGENT_MODEL || \"minimax/minimax-m3:free\";"
        }

        val outcome = migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        assertTrue(api.putContents.isEmpty())
        assertEquals(listOf(".github/scripts/ai-role-review.js"), outcome.unchanged)
        assertTrue(!outcome.didChange && !outcome.isNotAnOpenRouterRepo)
    }

    @Test
    fun `a workflow with no AGENT_MODEL default is left alone`() = runBlocking {
        // The first generation of MIA workflows ran on the Gemini CLI: there is no OpenRouter
        // model in them to repoint, and inserting one would produce a broken workflow.
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] =
                "env:\n  GEMINI_MODEL: \${{ vars.GEMINI_MODEL || 'gemini-2.5-flash' }}"
        }

        val outcome = migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        assertTrue(api.putContents.isEmpty())
        assertEquals(listOf(".github/workflows/agent-issue-worker.yml"), outcome.withoutModel)
        assertTrue(outcome.isNotAnOpenRouterRepo)
    }

    @Test
    fun `files this repo does not have are reported as missing, not as failures`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] = roleReviewJs
        }

        val outcome = migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        assertEquals(2, outcome.missing.size)
        assertTrue(outcome.failed.isEmpty())
        assertTrue(outcome.didChange)
    }

    @Test
    fun `a rejected write is reported per file instead of throwing`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] = roleReviewJs
            putContentResponse = { FakeGitHubApi.error(409) }
        }

        val outcome = migrator(api).setModel("octocat", "repo", "minimax/minimax-m3:free")

        assertTrue(outcome.updated.isEmpty())
        assertEquals(".github/scripts/ai-role-review.js", outcome.failed.single().first)
        assertTrue(outcome.failed.single().second.contains("409"))
    }

    @Test
    fun `the current model is read back without OpenCode's provider prefix`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = workerYml
        }

        // Comparable with an AgentModel.id, which is what the picker preselects on.
        assertEquals("stealth/ox-alpha", migrator(api).currentModel("octocat", "repo"))
    }

    @Test
    fun `a repo with none of the files reports no current model`() = runBlocking {
        assertNull(migrator(FakeGitHubApi()).currentModel("octocat", "repo"))
    }
}
