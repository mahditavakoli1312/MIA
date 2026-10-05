package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ProjectType
import ir.mahditavakoli.mia.network.github.CreateLabelBody
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GenerateFromTemplateBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubRepo
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.github.PutSecretBody
import ir.mahditavakoli.mia.network.github.RepoPublicKey
import ir.mahditavakoli.mia.network.github.WorkflowPermissions
import ir.mahditavakoli.mia.network.openrouter.AgentProvider

/** Turns bytes into a base64 string. Abstracted so unit tests avoid `android.util.Base64`. */
fun interface Base64Encoder {
    fun encode(bytes: ByteArray): String
}

/**
 * The other direction, for reading a file back out of the GitHub contents API. Abstracted for
 * the same reason as [Base64Encoder]: `android.util.Base64` is a stub on the JVM test runtime.
 */
fun interface Base64Decoder {
    fun decode(value: String): ByteArray
}

/**
 * Seals [plaintext] for GitHub Actions using a libsodium sealed box against the repo's
 * base64 Curve25519 public key, returning base64 ciphertext. Abstracted so the native
 * libsodium dependency stays out of host unit tests.
 */
fun interface SecretEncryptor {
    fun seal(plaintext: String, publicKeyBase64: String): String
}

/** A file to commit into a freshly created repo. [repoPath] is the repo-relative path. */
data class BootstrapFile(val repoPath: String, val content: String)

/**
 * Wires a freshly created repository up to the whole MIA "AI team":
 *   1. creates the repo (plain, or from [MIA_TEMPLATE_REPO] if set),
 *   2. turns on "Allow GitHub Actions to create and approve pull requests", without which TEC
 *      can commit a branch but not open the pull request that carries it,
 *   3. commits the [files] — the TEC coding agent, the PO/QC advisor workflow + script, the PO
 *      brief decomposer, the add-to-project and CI workflows (skipped for the template route,
 *      since the template already carries them),
 *   4. creates the queue and brief labels (see [LABELS]),
 *   5. stores the caller's OpenRouter API key as the `OPENROUTER_API_KEY` Actions secret
 *      (the free-model key that powers CI + PO + TEC + QC), plus the spare key as
 *      `OPENROUTER_API_KEY_FALLBACK` for the workflows to switch to on a 429, and the MiniMax
 *      key as `MINIMAX_API_KEY` so the repo can be repointed at a paid MiniMax model later
 *      without the user having to touch GitHub's own Settings.
 *
 * Repo creation is the only hard-failure step; everything after it is best-effort and
 * reported as [Result.warnings] so a half-wired repo still surfaces useful feedback
 * instead of throwing the whole operation away.
 */
class RepoBootstrapper(
    private val api: GitHubApi,
    private val base64: Base64Encoder,
    private val encryptor: SecretEncryptor,
    /** The files a new repo of a given kind gets — see [ProjectType]. */
    private val files: (ProjectType) -> List<BootstrapFile>,
    private val templateRepo: String = MIA_TEMPLATE_REPO
) {

    /** [repo] is always present (creation succeeded); [warnings] lists best-effort steps that failed. */
    data class Result(val repo: GitHubRepo, val warnings: List<String>)

    /**
     * @param owner the authenticated user (repo owner / secrets scope).
     * @param agentApiKey the OpenRouter key to store as the Actions secret; when null/blank
     *        the secret step is skipped with a warning (the workflow can't run without it).
     * @param agentFallbackApiKey the spare OpenRouter key the workflows retry on once the
     *        primary one is rate limited. Optional — null/blank just means no second attempt.
     * @param miniMaxApiKey the MiniMax platform key, stored so that switching this repo to a
     *        MiniMax model in the picker later just works. Optional and non-fatal: a repo on a
     *        free OpenRouter model never reads it.
     * @param projectType what kind of product this repo will hold, which decides the conventions
     *        file and the design system that land in it. The `.github/` machinery does not vary:
     *        those workflows detect what they are looking at on their own.
     */
    suspend fun bootstrap(
        owner: String,
        name: String,
        description: String?,
        private: Boolean,
        agentApiKey: String?,
        agentFallbackApiKey: String? = null,
        miniMaxApiKey: String? = null,
        projectType: ProjectType = ProjectType.DEFAULT
    ): Result {
        val useTemplate = templateRepo.isNotBlank()
        val repo = if (useTemplate) {
            val (tOwner, tRepo) = parseTemplate(templateRepo)
            api.generateFromTemplate(
                templateOwner = tOwner,
                templateRepo = tRepo,
                body = GenerateFromTemplateBody(name = name, description = description, private = private)
            )
        } else {
            api.createRepo(CreateRepoBody(name = name, description = description, private = private))
        }

        val warnings = mutableListOf<String>()

        // 2. Let Actions open pull requests. First, and outside the template branch, because it
        // is the one setting TEC cannot work around: without it `gh pr create` is refused and
        // every issue ends as a pushed branch with an apologetic comment. Doing it before the
        // workflow files land also means the switch is already on if uploading them triggers a
        // run. Best-effort like everything else after creation — an org that forbids it wins,
        // and the warning says so.
        allowActionsToOpenPullRequests(owner, repo.name)?.let { warnings += it }

        // 3. Team files — only when we didn't clone a template that already carries them.
        if (!useTemplate) {
            // Read per bootstrap, not once at construction: the files carry the per-role model
            // defaults the user has chosen, and those can change between two projects being
            // created without the app being restarted — and the set itself now depends on the
            // kind of project being created.
            for (file in files(projectType)) {
                runCatching {
                    val response = api.putContent(
                        owner = owner,
                        repo = repo.name,
                        path = file.repoPath,
                        body = PutContentBody(
                            message = "chore(mia): add ${file.repoPath}",
                            content = base64.encode(file.content.toByteArray(Charsets.UTF_8))
                        )
                    )
                    check(response.isSuccessful) { "HTTP ${response.code()}" }
                }.onFailure { warnings += "upload of «${file.repoPath}» failed (${it.message})" }
            }
        }

        // 4. Labels — 422 means it already exists, which is fine.
        for ((label, color) in LABELS) {
            runCatching {
                val response = api.createLabel(owner, repo.name, CreateLabelBody(name = label, color = color))
                check(response.isSuccessful || response.code() == 422) { "HTTP ${response.code()}" }
            }.onFailure { warnings += "label «$label» failed (${it.message})" }
        }

        // 5. Model API key secrets (used by the OpenCode agent in the workflow). All are sealed
        // against the same repo public key, so fetch it once and reuse it.
        if (agentApiKey.isNullOrBlank() && miniMaxApiKey.isNullOrBlank()) {
            warnings += "OPENROUTER_API_KEY not set — add it in Settings so the agent can run"
        } else {
            runCatching {
                val publicKey = api.getRepoPublicKey(owner, repo.name)
                if (!agentApiKey.isNullOrBlank()) {
                    putSecret(owner, repo.name, SECRET_NAME, agentApiKey, publicKey)
                }
                // Optional: without it the workflows just stop at the first 429, as before.
                if (!agentFallbackApiKey.isNullOrBlank() && agentFallbackApiKey != agentApiKey) {
                    runCatching {
                        putSecret(owner, repo.name, FALLBACK_SECRET_NAME, agentFallbackApiKey, publicKey)
                    }.onFailure {
                        warnings += "setting $FALLBACK_SECRET_NAME secret failed (${it.message})"
                    }
                }
                // Also optional: only read once the repo is pointed at a MiniMax model, but
                // storing it now means that switch is a one-tap change in the picker later.
                if (!miniMaxApiKey.isNullOrBlank()) {
                    runCatching {
                        putSecret(owner, repo.name, MINIMAX_SECRET_NAME, miniMaxApiKey, publicKey)
                    }.onFailure {
                        warnings += "setting $MINIMAX_SECRET_NAME secret failed (${it.message})"
                    }
                }
            }.onFailure { warnings += "setting model API key secrets failed (${it.message})" }
        }

        return Result(repo, warnings)
    }

    /**
     * Turns on "Allow GitHub Actions to create and approve pull requests" for [repo], returning
     * null on success and a warning line otherwise.
     *
     * TEC's whole output is a pull request. The `GITHUB_TOKEN` it runs under is refused
     * `gh pr create` while this repository setting is off — not by the workflow's own
     * `permissions:` block, which already asks for `pull-requests: write`, but by the account
     * setting above it — so a fresh repo would do all of the work, push the branch, and then
     * comment that a human has to go and flip a switch in Settings before any of it can land.
     *
     * The current block is read first so that `default_workflow_permissions` can be written
     * back exactly as found. That field is not MIA's business: every workflow MIA installs
     * declares its own `permissions:`, so the default never applies to them, and a user who
     * has deliberately set their repos to a read-only default should not have it widened as a
     * side effect of creating a project.
     *
     * Never throws. An organization can forbid this setting outright, and a fine-grained token
     * may lack the administration scope; both are worth a warning and neither is worth failing
     * a repo that is otherwise fully wired up.
     */
    private suspend fun allowActionsToOpenPullRequests(owner: String, repo: String): String? =
        runCatching {
            val current = api.getWorkflowPermissions(owner, repo).body()
            if (current?.canApprovePullRequestReviews == true) return null
            val response = api.putWorkflowPermissions(
                owner = owner,
                repo = repo,
                body = WorkflowPermissions(
                    // Null when the read failed; GitHub then leaves the field as it is.
                    defaultWorkflowPermissions = current?.defaultWorkflowPermissions,
                    canApprovePullRequestReviews = true
                )
            )
            check(response.isSuccessful) { "HTTP ${response.code()}" }
            null
        }.getOrElse {
            "could not allow Actions to open pull requests (${it.message}) — " +
                "enable it in Settings → Actions → General → Workflow permissions, " +
                "or TEC will push a branch but not open a PR"
        }

    /**
     * Stores one provider's API key on an *existing* repo, for the model picker: repointing a
     * repo at MiniMax is useless if the workflow then finds no `MINIMAX_API_KEY` to spend, and
     * the user should not have to go to GitHub's own Settings to finish a change MIA started.
     *
     * Never throws — a repo whose secrets MIA cannot write (a fork, a revoked token scope) still
     * gets the file rewrite, and the caller reports the shortfall.
     */
    suspend fun putProviderSecret(
        owner: String,
        repo: String,
        provider: AgentProvider,
        value: String
    ): kotlin.Result<Unit> = runCatching {
        putSecret(owner, repo, provider.secretName, value, api.getRepoPublicKey(owner, repo))
    }

    private suspend fun putSecret(
        owner: String,
        repo: String,
        name: String,
        value: String,
        publicKey: RepoPublicKey
    ) {
        val response = api.putActionsSecret(
            owner = owner,
            repo = repo,
            name = name,
            body = PutSecretBody(
                encryptedValue = encryptor.seal(value, publicKey.key),
                keyId = publicKey.keyId
            )
        )
        check(response.isSuccessful) { "HTTP ${response.code()}" }
    }

    private fun parseTemplate(value: String): Pair<String, String> {
        val parts = value.split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "MIA_TEMPLATE_REPO must be in the form \"owner/repo\", was «$value»"
        }
        return parts[0] to parts[1]
    }

    companion object {
        /**
         * Set to "owner/template-repo" to create every MIA repo from that template
         * (which must already contain the workflow) instead of plain creation + upload.
         * Empty means plain creation.
         */
        const val MIA_TEMPLATE_REPO = ""

        const val SECRET_NAME = "OPENROUTER_API_KEY"

        /** The spare key the AI-team workflows retry with when [SECRET_NAME] hits a 429. */
        const val FALLBACK_SECRET_NAME = "OPENROUTER_API_KEY_FALLBACK"

        /** The paid MiniMax platform key, read only by repos pointed at a MiniMax model. */
        const val MINIMAX_SECRET_NAME = "MINIMAX_API_KEY"

        /**
         * The labels the pipeline runs on, created up front so the workflows can move an issue
         * between them (adding a label to an issue does not create a missing one). GitHub label
         * colors are 6-digit hex without a leading '#'.
         *
         * The first four are the TEC queue: `by-agent` means queued, `agent-running` means
         * claimed, and the next two are the ways it ends. Then the brief pipeline: `brief` is an
         * intent waiting to be decomposed, `brief-planned`/`brief-failed` are how that ended, and
         * `blocked` marks a child issue whose prerequisites have not landed. The last four are the
         * QC gate on TEC's pull requests: `qc-approved` / `qc-skipped` let a merge through,
         * `needs-rework` sends the issue back for another attempt, `needs-po` hands it to the PO
         * to re-scope once two attempts were not enough — which is how the loop keeps going
         * instead of dropping the work — and `needs-human` is the one label that takes an issue
         * out of the queue, reached only when a repo set an `AGENT_MAX_CYCLES` ceiling.
         */
        val LABELS = listOf(
            "by-agent" to "1d76db",
            "agent-running" to "fbca04",
            "agent-failed" to "b60205",
            "done" to "0e8a16",
            "brief" to "6f42c1",
            "brief-planned" to "5319e7",
            "brief-failed" to "b60205",
            "blocked" to "d93f0b",
            "qc-approved" to "0e8a16",
            "qc-skipped" to "bfd4f2",
            "needs-rework" to "d93f0b",
            "needs-po" to "5319e7",
            "needs-human" to "b60205"
        )
    }
}
