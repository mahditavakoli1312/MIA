package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateLabelBody
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GenerateFromTemplateBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubRepo
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.github.PutSecretBody

/** Turns bytes into a base64 string. Abstracted so unit tests avoid `android.util.Base64`. */
fun interface Base64Encoder {
    fun encode(bytes: ByteArray): String
}

/**
 * Seals [plaintext] for GitHub Actions using a libsodium sealed box against the repo's
 * base64 Curve25519 public key, returning base64 ciphertext. Abstracted so the native
 * libsodium dependency stays out of host unit tests.
 */
fun interface SecretEncryptor {
    fun seal(plaintext: String, publicKeyBase64: String): String
}

/**
 * Wires a freshly created repository up to the Gemini CI agent:
 *   1. creates the repo (plain, or from [MIA_TEMPLATE_REPO] if set),
 *   2. uploads the `agent-issue-worker.yml` workflow (skipped for the template route,
 *      since the template already carries it),
 *   3. creates the `by-agent` / `done` labels,
 *   4. stores the caller's Gemini API key as the `GEMINI_API_KEY` Actions secret.
 *
 * Repo creation is the only hard-failure step; everything after it is best-effort and
 * reported as [Result.warnings] so a half-wired repo still surfaces useful feedback
 * instead of throwing the whole operation away.
 */
class RepoBootstrapper(
    private val api: GitHubApi,
    private val base64: Base64Encoder,
    private val encryptor: SecretEncryptor,
    private val workflowYaml: String,
    private val templateRepo: String = MIA_TEMPLATE_REPO
) {

    /** [repo] is always present (creation succeeded); [warnings] lists best-effort steps that failed. */
    data class Result(val repo: GitHubRepo, val warnings: List<String>)

    /**
     * @param owner the authenticated user (repo owner / secrets scope).
     * @param geminiApiKey the key to store as the Actions secret; when null/blank the
     *        secret step is skipped with a warning (the workflow can't run without it).
     */
    suspend fun bootstrap(
        owner: String,
        name: String,
        description: String?,
        private: Boolean,
        geminiApiKey: String?
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

        // 1. Workflow file — only when we didn't clone a template that already has it.
        if (!useTemplate) {
            runCatching {
                val response = api.putContent(
                    owner = owner,
                    repo = repo.name,
                    path = WORKFLOW_PATH,
                    body = PutContentBody(
                        message = "chore: add MIA agent issue worker",
                        content = base64.encode(workflowYaml.toByteArray(Charsets.UTF_8))
                    )
                )
                check(response.isSuccessful) { "HTTP ${response.code()}" }
            }.onFailure { warnings += "workflow upload failed (${it.message})" }
        }

        // 2. Labels — 422 means it already exists, which is fine.
        for ((label, color) in LABELS) {
            runCatching {
                val response = api.createLabel(owner, repo.name, CreateLabelBody(name = label, color = color))
                check(response.isSuccessful || response.code() == 422) { "HTTP ${response.code()}" }
            }.onFailure { warnings += "label «$label» failed (${it.message})" }
        }

        // 3. Gemini API key secret.
        if (geminiApiKey.isNullOrBlank()) {
            warnings += "GEMINI_API_KEY not set — add it in Settings so the agent can run"
        } else {
            runCatching {
                val publicKey = api.getRepoPublicKey(owner, repo.name)
                val response = api.putActionsSecret(
                    owner = owner,
                    repo = repo.name,
                    name = SECRET_NAME,
                    body = PutSecretBody(
                        encryptedValue = encryptor.seal(geminiApiKey, publicKey.key),
                        keyId = publicKey.keyId
                    )
                )
                check(response.isSuccessful) { "HTTP ${response.code()}" }
            }.onFailure { warnings += "setting GEMINI_API_KEY secret failed (${it.message})" }
        }

        return Result(repo, warnings)
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

        const val WORKFLOW_PATH = ".github/workflows/agent-issue-worker.yml"
        const val SECRET_NAME = "GEMINI_API_KEY"

        /** GitHub label colors are 6-digit hex without a leading '#'. */
        val LABELS = listOf(
            "by-agent" to "1d76db",
            "done" to "0e8a16"
        )
    }
}
