package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- provider awareness ---------------------------------------------------------------
    //
    // A model can now be reached through more than one service, so a file names both an id and
    // the provider that serves it. These cover the pair moving together, and the one genuinely
    // ambiguous id in the set.

    /** The current shape: an AGENT_PROVIDER default next to each AGENT_MODEL one. */
    private val providerAwareWorkerYml = """
        jobs:
          tec:
            env:
              AGENT_PROVIDER: ${'$'}{{ vars.AGENT_PROVIDER || 'openrouter' }}
            steps:
              - name: Run OpenCode CLI
                env:
                  AGENT_MODEL: ${'$'}{{ vars.AGENT_MODEL || 'openrouter/minimax/minimax-m3:free' }}
    """.trimIndent()

    private val providerAwareRoleReviewJs = """
        const model = process.env.AGENT_MODEL || "minimax/minimax-m3:free";
        const providerId = process.env.AGENT_PROVIDER || "openrouter";
    """.trimIndent()

    @Test
    fun `switching to MiniMax moves the model and the provider together`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = providerAwareWorkerYml
            contents[".github/scripts/ai-role-review.js"] = providerAwareRoleReviewJs
        }

        val outcome = migrator(api).setModel("octocat", "repo", "MiniMax-M3")

        // OpenCode gets provider + model; the direct-API script gets the bare id. Both files
        // must also agree on AGENT_PROVIDER, or one of them spends the wrong key.
        val yml = api.written(".github/workflows/agent-issue-worker.yml")
        assertTrue(yml.contains("vars.AGENT_MODEL || 'minimax/MiniMax-M3' }}"))
        assertTrue(yml.contains("vars.AGENT_PROVIDER || 'minimax' }}"))

        val js = api.written(".github/scripts/ai-role-review.js")
        assertTrue(js.contains("process.env.AGENT_MODEL || \"MiniMax-M3\";"))
        assertTrue(js.contains("process.env.AGENT_PROVIDER || \"minimax\";"))

        assertEquals(AgentProvider.MINIMAX, outcome.provider)
        assertTrue(outcome.withoutProvider.isEmpty())
    }

    @Test
    fun `the OpenRouter id that looks provider-qualified is still written bare`() = runBlocking {
        // `minimax/minimax-m3:free` is a *model id* on OpenRouter, not a provider/model pair —
        // the one case where deciding "is this qualified?" by looking for a slash gets it wrong,
        // and would leave the PO/QC script calling a model named "minimax-m3:free".
        val api = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] = providerAwareRoleReviewJs
            contents[".github/workflows/agent-issue-worker.yml"] = providerAwareWorkerYml
        }

        migrator(api).setModel("octocat", "repo", "MiniMax-M3")
        // Now back again — the return trip is where a bad round-trip would surface.
        val api2 = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] =
                api.written(".github/scripts/ai-role-review.js")
            contents[".github/workflows/agent-issue-worker.yml"] =
                api.written(".github/workflows/agent-issue-worker.yml")
        }
        migrator(api2).setModel("octocat", "repo", "minimax/minimax-m3:free")

        assertTrue(
            api2.written(".github/scripts/ai-role-review.js")
                .contains("process.env.AGENT_MODEL || \"minimax/minimax-m3:free\";")
        )
        assertTrue(
            api2.written(".github/workflows/agent-issue-worker.yml")
                .contains("vars.AGENT_MODEL || 'openrouter/minimax/minimax-m3:free' }}")
        )
    }

    @Test
    fun `currentModel strips a MiniMax provider prefix`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] =
                "AGENT_MODEL: ${'$'}{{ vars.AGENT_MODEL || 'minimax/MiniMax-M3' }}"
        }

        // Comparable with an AgentModel.id, so the picker can preselect the row it is on.
        assertEquals("MiniMax-M3", migrator(api).currentModel("octocat", "repo"))
    }

    @Test
    fun `an older repo without AGENT_PROVIDER is flagged, not silently half-migrated`() =
        runBlocking {
            // The model lands, but these files have no provider default to carry it — they will
            // keep calling OpenRouter with a MiniMax id. The UI has to be able to say so.
            val api = FakeGitHubApi().apply {
                contents[".github/scripts/ai-role-review.js"] = roleReviewJs
            }

            val outcome = migrator(api).setModel("octocat", "repo", "MiniMax-M3")

            assertTrue(outcome.didChange)
            assertEquals(listOf(".github/scripts/ai-role-review.js"), outcome.withoutProvider)
            assertTrue(outcome.needsProviderAwareFiles)
        }

    @Test
    fun `staying on OpenRouter never reports a provider shortfall`() = runBlocking {
        // Same old files, but the target is served by the provider they already assume — so
        // there is nothing missing and nothing to warn about.
        val api = FakeGitHubApi().apply {
            contents[".github/scripts/ai-role-review.js"] = roleReviewJs
        }

        val outcome = migrator(api).setModel("octocat", "repo", "z-ai/glm-5.2:free")

        assertEquals(listOf(".github/scripts/ai-role-review.js"), outcome.withoutProvider)
        assertFalse(outcome.needsProviderAwareFiles)
    }

}
