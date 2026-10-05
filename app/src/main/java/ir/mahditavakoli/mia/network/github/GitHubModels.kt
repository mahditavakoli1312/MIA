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

/**
 * GET /repos/{owner}/{repo}/contents/{path} — one file's content and its blob SHA.
 *
 * [content] is base64 and GitHub wraps it at 60 columns, so it must be decoded with the line
 * breaks stripped (or a MIME decoder). [sha] is what a later PUT has to echo back to prove it
 * is replacing the version it read, instead of clobbering someone else's commit.
 */
@Serializable
data class ContentFile(
    val path: String,
    val sha: String,
    val content: String = "",
    /** "base64" for normal files; "none" when the blob is too large to inline (>1 MB). */
    val encoding: String = "base64"
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

/**
 * GET/PUT /repos/{owner}/{repo}/actions/permissions/workflow — the "Workflow permissions"
 * block of a repo's Actions settings.
 *
 * MIA only cares about [canApprovePullRequestReviews], the "Allow GitHub Actions to create and
 * approve pull requests" switch: with it off, `gh pr create` under `GITHUB_TOKEN` is refused
 * outright, so TEC commits a branch and then cannot open the pull request it just prepared.
 * [defaultWorkflowPermissions] is read only so it can be written back untouched — see
 * [ir.mahditavakoli.mia.data.repository.RepoBootstrapper.allowActionsToOpenPullRequests].
 */
@Serializable
data class WorkflowPermissions(
    /** "read" or "write" — the token scope a workflow gets when it declares none itself. */
    @SerialName("default_workflow_permissions") val defaultWorkflowPermissions: String? = null,
    @SerialName("can_approve_pull_request_reviews")
    val canApprovePullRequestReviews: Boolean = false
)

/**
 * One issue as the list/detail endpoints return it.
 *
 * The list endpoint (`GET /repos/{owner}/{repo}/issues`) also returns pull requests — GitHub
 * models a PR as an issue — and the only way to tell them apart is [pullRequest], which is
 * present on PRs and absent on real issues. Callers that want issues must filter on it.
 */
@Serializable
data class GitHubIssueDetail(
    val number: Int,
    val title: String,
    val body: String? = null,
    /** "open" or "closed". */
    val state: String = "open",
    val user: GitHubUser? = null,
    val labels: List<GitHubLabel> = emptyList(),
    /** How many comments the issue has, so the list can show a count without a second call. */
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    /** Non-null only when this row is really a pull request. */
    @SerialName("pull_request") val pullRequest: PullRequestRef? = null
)

/** Marker object GitHub attaches to issue rows that are actually pull requests. */
@Serializable
data class PullRequestRef(
    @SerialName("html_url") val htmlUrl: String = ""
)

@Serializable
data class GitHubLabel(
    val name: String,
    /** Six hex digits without a leading '#'. */
    val color: String = ""
)

/**
 * PATCH /repos/{owner}/{repo}/issues/{number} — only the fields being changed are sent.
 *
 * [state] is "open" or "closed". GitHub also accepts a `state_reason`, but leaving it off lets
 * GitHub pick its own default ("completed" when closing), which is what a task MIA closes means.
 */
@Serializable
data class UpdateIssueBody(val state: String)

/** GET/POST /repos/{owner}/{repo}/issues/{number}/comments */
@Serializable
data class GitHubIssueComment(
    val id: Long,
    val body: String = "",
    val user: GitHubUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    /**
     * ".../issues/12" — present on the repo-wide comments endpoint, which is how a comment read
     * without asking for a specific issue still knows which issue it belongs to. The per-issue
     * endpoint returns it too; the caller there already knows the number and ignores it.
     */
    @SerialName("issue_url") val issueUrl: String? = null
)

/**
 * One commit, as the spend screen reads them: GET /repos/{owner}/{repo}/commits.
 *
 * Only three things are needed — the message (which carries the `Token-Spend:` trailer the TEC
 * workflow writes), when it landed, and a link — so the rest of GitHub's very large commit object
 * is deliberately not modelled.
 */
@Serializable
data class GitHubCommit(
    val sha: String = "",
    val commit: GitHubCommitDetail = GitHubCommitDetail(),
    @SerialName("html_url") val htmlUrl: String = ""
)

@Serializable
data class GitHubCommitDetail(
    val message: String = "",
    val author: GitHubCommitAuthor? = null
)

@Serializable
data class GitHubCommitAuthor(
    /** ISO-8601, e.g. "2026-09-01T10:22:03Z". */
    val date: String? = null
)

/** POST /repos/{owner}/{repo}/issues/{number}/comments */
@Serializable
data class CreateCommentBody(val body: String)
