package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.openrouter.AgentRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The updater overwrites files in *other people's repos*, so these tests care about the two ways
 * that can go wrong quietly: touching something it was never given (a project's own AGENTS.md or
 * design sources), and losing the models a repo was deliberately put on.
 */
class TeamFilesUpdaterTest {

    private val jvmEncoder = Base64Encoder { bytes -> Base64.getEncoder().encodeToString(bytes) }
    private val jvmDecoder = Base64Decoder { value -> Base64.getDecoder().decode(value) }

    /** A current-generation worker: one named ladder per role it runs. */
    private val newWorkerYml = """
        jobs:
          tec:
            env:
              AGENT_PROVIDER: ${'$'}{{ vars.AGENT_PROVIDER_TEC || vars.AGENT_PROVIDER || 'openrouter' }}
            steps:
              - name: Run the model ladder
                env:
                  AGENT_MODEL: ${'$'}{{ vars.AGENT_MODEL_TEC || vars.AGENT_MODEL || 'openrouter/minimax/minimax-m3:free' }}
              - name: QC gate
                env:
                  AGENT_MODEL_QC: ${'$'}{{ vars.AGENT_MODEL_QC || vars.AGENT_MODEL || 'minimax/minimax-m3:free' }}
                  AGENT_PROVIDER_QC: ${'$'}{{ vars.AGENT_PROVIDER_QC || vars.AGENT_PROVIDER || 'openrouter' }}
        # new in this build
    """.trimIndent()

    /** What a pre-role repo has: one shared default for everything in the file. */
    private val oldWorkerYml = """
        jobs:
          tec:
            steps:
              - name: Run OpenCode CLI
                env:
                  AGENT_MODEL: ${'$'}{{ vars.AGENT_MODEL || 'openrouter/z-ai/glm-5.2:free' }}
    """.trimIndent()

    private val files = listOf(
        BootstrapFile(".github/workflows/agent-issue-worker.yml", newWorkerYml),
        BootstrapFile(".github/scripts/po-rebrief.js", "// po rebrief\n"),
        BootstrapFile("AGENTS.md", "# conventions the project owns\n"),
        BootstrapFile("app/src/main/java/mia/design/Tokens.kt", "// design tokens\n")
    )

    private fun updater(api: FakeGitHubApi) = TeamFilesUpdater(
        api = api,
        base64 = jvmEncoder,
        base64Decoder = jvmDecoder,
        currentFiles = { files },
        migrator = AgentModelMigrator(api, jvmEncoder, jvmDecoder)
    )

    private fun FakeGitHubApi.written(path: String): String =
        String(Base64.getDecoder().decode(putContents.first { it.first == path }.second.content))

    private val appDefaults = AgentRole.REPO_ROLES.associateWith { "minimax/minimax-m3:free" }

    @Test
    fun `only files under dot-github are touched`() = runBlocking {
        // AGENTS.md and the design sources are a starting point the project owns from then on.
        // Overwriting them would be MIA taking back something it gave away.
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = oldWorkerYml
            contents["AGENTS.md"] = "# a project's own, heavily edited conventions\n"
            contents["app/src/main/java/mia/design/Tokens.kt"] = "// edited by the project\n"
        }

        updater(api).update("octocat", "repo", appDefaults)

        val touched = api.putContents.map { it.first }
        assertTrue(touched.all { it.startsWith(".github/") })
        assertFalse("AGENTS.md" in touched)
        assertFalse("app/src/main/java/mia/design/Tokens.kt" in touched)
    }

    @Test
    fun `a pre-role repo keeps the model it was actually running`() = runBlocking {
        // The repo is on GLM. The app's default is MiniMax. Updating the machinery must not
        // quietly move every role onto the app's default — that is somebody's bill.
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = oldWorkerYml
        }

        val outcome = updater(api).update("octocat", "repo", appDefaults)

        val after = api.written(".github/workflows/agent-issue-worker.yml")
        assertTrue(after.contains("vars.AGENT_MODEL_TEC || vars.AGENT_MODEL || 'openrouter/z-ai/glm-5.2:free'"))
        assertEquals("z-ai/glm-5.2:free", outcome.models[AgentRole.TEC])
        // The file now carries a per-role ladder, which is the whole point of updating it.
        assertTrue(after.contains("vars.AGENT_MODEL_QC"))
    }

    @Test
    fun `a pre-role repo's shared model is kept for every role, not just TEC`() = runBlocking {
        // A repo with one shared AGENT_MODEL really is running every seat on it — that is what
        // "shared" means — so the update keeps all four there rather than moving the three the
        // old file does not mention by name onto the app's default.
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = oldWorkerYml
        }

        val outcome = updater(api).update(
            "octocat",
            "repo",
            appDefaults + (AgentRole.BRIEF to "cohere/north-mini-code:free")
        )

        assertEquals(
            AgentRole.REPO_ROLES.associateWith { "z-ai/glm-5.2:free" },
            outcome.models
        )
    }

    @Test
    fun `the app default fills in only when the repo names no model at all`() = runBlocking {
        // The first generation of workflows ran on the Gemini CLI: there is no model default in
        // them to carry across, so there is nothing to preserve and the app's choice is the
        // only answer available.
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] =
                "env:\n  GEMINI_MODEL: \${{ vars.GEMINI_MODEL || 'gemini-2.5-flash' }}"
        }

        val outcome = updater(api).update(
            "octocat",
            "repo",
            appDefaults + (AgentRole.BRIEF to "cohere/north-mini-code:free")
        )

        assertEquals("cohere/north-mini-code:free", outcome.models[AgentRole.BRIEF])
        assertEquals("minimax/minimax-m3:free", outcome.models[AgentRole.TEC])
        // And the repo now has files that can actually hold those choices.
        assertTrue(
            api.written(".github/workflows/agent-issue-worker.yml")
                .contains("vars.AGENT_MODEL_TEC")
        )
    }

    @Test
    fun `a file the repo does not have is added, not reported as an update`() = runBlocking {
        // "You now have a PO re-scoper you did not have" is bigger news than "yours is newer".
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = oldWorkerYml
        }

        val outcome = updater(api).update("octocat", "repo", appDefaults)

        assertEquals(listOf(".github/scripts/po-rebrief.js"), outcome.added)
        assertEquals(listOf(".github/workflows/agent-issue-worker.yml"), outcome.updated)
        assertTrue(outcome.didChange)
    }

    @Test
    fun `an already-current repo is not rewritten`() = runBlocking {
        // Committing a byte-identical file would put a meaningless commit in someone's history
        // every time they tapped the button.
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = newWorkerYml
            contents[".github/scripts/po-rebrief.js"] = "// po rebrief\n"
        }

        val outcome = updater(api).update("octocat", "repo", appDefaults)

        assertTrue(api.putContents.isEmpty())
        assertEquals(2, outcome.unchanged.size)
        assertFalse(outcome.didChange)
    }

    @Test
    fun `the blob sha is echoed back so a concurrent edit is not clobbered`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = oldWorkerYml
        }

        updater(api).update("octocat", "repo", appDefaults)

        val body = api.putContents.first { it.first.endsWith("agent-issue-worker.yml") }.second
        assertEquals("sha-" + ".github/workflows/agent-issue-worker.yml".hashCode(), body.sha)
        // A file being created has no sha to send, and sending one would fail the write.
        val added = api.putContents.first { it.first.endsWith("po-rebrief.js") }.second
        assertEquals(null, added.sha)
    }

    @Test
    fun `a rejected write is reported per file instead of throwing`() = runBlocking {
        val api = FakeGitHubApi().apply {
            contents[".github/workflows/agent-issue-worker.yml"] = oldWorkerYml
            putContentResponse = { FakeGitHubApi.error(409) }
        }

        val outcome = updater(api).update("octocat", "repo", appDefaults)

        assertTrue(outcome.updated.isEmpty())
        assertEquals(2, outcome.failed.size)
        assertTrue(outcome.failed.all { it.second.contains("409") })
    }
}
