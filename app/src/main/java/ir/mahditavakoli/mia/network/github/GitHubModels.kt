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
    val owner: GitHubOwner
)

@Serializable
data class GitHubOwner(val login: String)

@Serializable
data class GitHubUser(val login: String)

@Serializable
data class CreateIssueBody(
    val title: String,
    val body: String? = null
)

@Serializable
data class GitHubIssue(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String
)
