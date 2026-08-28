package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateLabelBody
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GenerateFromTemplateBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubRepo
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.github.PutSecretBody
import ir.mahditavakoli.mia.network.github.RepoPublicKey

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
 *   2. commits the [files] — the TEC coding agent, the PO/QC advisor workflow + script, the
 *      add-to-project and CI workflows (skipped for the template route, since the template
 *      already carries them),
 *   3. creates the `by-agent` / `done` labels,
 *   4. stores the caller's OpenRouter API key as the `OPENROUTER_API_KEY` Actions secret
 *      (the single free-model key that powers CI + PO + TEC + QC), plus the spare key as
 *      `OPENROUTER_API_KEY_FALLBACK` for the workflows to switch to on a 429.
 *
 * Repo creation is the only hard-failure step; everything after it is best-effort and
 * reported as [Result.warnings] so a half-wired repo still surfaces useful feedback
 * instead of throwing the whole operation away.
 */
class RepoBootstrapper(
    private val api: GitHubApi,
    private val base64: Base64Encoder,
    private val encryptor: SecretEncryptor,
    private val files: List<BootstrapFile>,
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
     */
    suspend fun bootstrap(
        owner: String,
        name: String,
        description: String?,
        private: Boolean,
        agentApiKey: String?,
        agentFallbackApiKey: String? = null
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

        // 1. Team files — only when we didn't clone a template that already carries them.
        if (!useTemplate) {
            for (file in files) {
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

        // 2. Labels — 422 means it already exists, which is fine.
        for ((label, color) in LABELS) {
            runCatching {
                val response = api.createLabel(owner, repo.name, CreateLabelBody(name = label, color = color))
                check(response.isSuccessful || response.code() == 422) { "HTTP ${response.code()}" }
            }.onFailure { warnings += "label «$label» failed (${it.message})" }
        }

        // 3. OpenRouter API key secrets (used by the OpenCode agent in the workflow). Both are
        // sealed against the same repo public key, so fetch it once and reuse it.
        if (agentApiKey.isNullOrBlank()) {
            warnings += "OPENROUTER_API_KEY not set — add it in Settings so the agent can run"
        } else {
            runCatching {
                val publicKey = api.getRepoPublicKey(owner, repo.name)
                putSecret(owner, repo.name, SECRET_NAME, agentApiKey, publicKey)
                // Optional: without it the workflows just stop at the first 429, as before.
                if (!agentFallbackApiKey.isNullOrBlank() && agentFallbackApiKey != agentApiKey) {
                    runCatching {
                        putSecret(owner, repo.name, FALLBACK_SECRET_NAME, agentFallbackApiKey, publicKey)
                    }.onFailure {
                        warnings += "setting $FALLBACK_SECRET_NAME secret failed (${it.message})"
                    }
                }
            }.onFailure { warnings += "setting OPENROUTER_API_KEY secret failed (${it.message})" }
        }

        return Result(repo, warnings)
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

        /** GitHub label colors are 6-digit hex without a leading '#'. */
        val LABELS = listOf(
            "by-agent" to "1d76db",
            "done" to "0e8a16"
        )
    }
}
