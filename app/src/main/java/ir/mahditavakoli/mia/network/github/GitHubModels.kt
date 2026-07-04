package ir.mahditavakoli.mia.network.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateRepoBody(
    val name: String,
    val description: String? = null,
    val private: Boolean = true,
    // Create an initial commit (README) so the repo is immediately clonable and
    // can accept issues without an extra setup step.
    @SerialName("auto_init") val autoInit: Boolean = true
)

@Serializable
data class GitHubRepo(
    val name: String,
    @SerialName("full_name") val fullName: String, // "owner/repo"
    @SerialName("html_url") val htmlUrl: String,
    val owner: GitHubOwner,
    // The branch the agent workflow checks out and pushes to; defaults to "main" for
    // freshly created repos, but read it back rather than assuming.
    @SerialName("default_branch") val defaultBranch: String = "main"
)

@Serializable
data class GitHubOwner(val login: String)

@Serializable
data class GitHubUser(val login: String)

@Serializable
data class CreateIssueBody(
    val title: String,
    val body: String? = null,
    // Labels attached at creation time. A "by-agent" label here is what fires the
    // agent workflow the moment the issue is opened.
    val labels: List<String>? = null
)

/** POST /repos/{templateOwner}/{templateRepo}/generate — create a repo from a template. */
@Serializable
data class GenerateFromTemplateBody(
    val name: String,
    val description: String? = null,
    val private: Boolean = true,
    // Copy full history so the template's workflow file/config land on the default branch.
    @SerialName("include_all_branches") val includeAllBranches: Boolean = false
)

/** PUT /repos/{owner}/{repo}/contents/{path} — create/update a single file. */
@Serializable
data class PutContentBody(
    val message: String,
    /** Base64-encoded file content. */
    val content: String,
    /** Blob SHA of the file being replaced; null when creating a new file. */
    val sha: String? = null
)

@Serializable
data class CreateLabelBody(
    val name: String,
    val color: String,
    val description: String? = null
)

/** GET /repos/{owner}/{repo}/actions/secrets/public-key */
@Serializable
data class RepoPublicKey(
    @SerialName("key_id") val keyId: String,
    /** Base64-encoded Curve25519 public key used to seal Actions secrets. */
    val key: String
)

/** PUT /repos/{owner}/{repo}/actions/secrets/{name} */
@Serializable
data class PutSecretBody(
    /** libsodium sealed-box ciphertext of the secret, base64-encoded. */
    @SerialName("encrypted_value") val encryptedValue: String,
    @SerialName("key_id") val keyId: String
)

@Serializable
data class GitHubIssue(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String
)
