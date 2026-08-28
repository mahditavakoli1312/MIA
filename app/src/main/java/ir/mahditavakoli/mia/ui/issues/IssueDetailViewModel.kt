package ir.mahditavakoli.mia.ui.issues

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.IssueComment
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.toPersianMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IssueDetailUiState(
    val projectName: String = "",
    val issueNumber: Int = 0,
    val issue: RepoIssue? = null,
    val comments: List<IssueComment> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** What the user has typed into the comment box but not yet sent. */
    val commentDraft: String = "",
    val isPostingComment: Boolean = false
) {
    val canPostComment: Boolean get() = commentDraft.isNotBlank() && !isPostingComment
}

/**
 * One issue: its body, its comment thread, and posting a new comment.
 *
 * The issue and its comments are fetched together — a detail screen is useless with only one of
 * the two, and doing them concurrently means the screen appears in one wait rather than two.
 */
class IssueDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val gitHubRepository = NetworkModule.gitHubRepository

    private val _uiState = MutableStateFlow(IssueDetailUiState())
    val uiState: StateFlow<IssueDetailUiState> = _uiState.asStateFlow()

    // Same one-shot snackbar pattern as MainViewModel: a Channel so a message isn't replayed
    // on rotation.
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadedKey: String? = null

    fun load(projectName: String, issueNumber: Int) {
        val key = "$projectName#$issueNumber"
        if (loadedKey == key) return
        loadedKey = key
        _uiState.update { it.copy(projectName = projectName, issueNumber = issueNumber) }
        refresh()
    }

    fun refresh() {
        val state = _uiState.value
        if (state.projectName.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val (issue, comments) = coroutineScope {
                val issueCall = async { gitHubRepository.issueFor(state.projectName, state.issueNumber) }
                val commentsCall =
                    async { gitHubRepository.issueCommentsFor(state.projectName, state.issueNumber) }
                issueCall.await() to commentsCall.await()
            }
            issue.fold(
                onSuccess = { loaded ->
                    _uiState.update {
                        it.copy(
                            issue = loaded,
                            // A thread that fails to load is not worth blocking the issue over —
                            // the body is the part the user came for, so show it and leave the
                            // comment list empty rather than erroring the whole screen.
                            comments = comments.getOrDefault(emptyList()),
                            isLoading = false
                        )
                    }
                    comments.onFailure { error ->
                        emitEvent(error.toPersianMessage("کامنت‌ها خوانده نشدند"))
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.toPersianMessage("خواندن این ایشو ناموفق بود")
                        )
                    }
                }
            )
        }
    }

    fun onCommentDraftChange(value: String) {
        _uiState.update { it.copy(commentDraft = value) }
    }

    /**
     * Posts the drafted comment. The draft is only cleared once GitHub has accepted it — losing
     * a comment the user typed because the network dropped is far worse than retyping nothing.
     */
    fun onPostComment() {
        val state = _uiState.value
        val draft = state.commentDraft.trim()
        if (draft.isEmpty() || state.isPostingComment) return
        _uiState.update { it.copy(isPostingComment = true) }
        viewModelScope.launch {
            gitHubRepository.addIssueComment(state.projectName, state.issueNumber, draft).fold(
                onSuccess = { comment ->
                    _uiState.update {
                        it.copy(
                            comments = it.comments + comment,
                            // Keep the count on the header honest without re-reading the issue.
                            issue = it.issue?.let { issue ->
                                issue.copy(commentCount = issue.commentCount + 1)
                            },
                            commentDraft = "",
                            isPostingComment = false
                        )
                    }
                    emitEvent("کامنت ثبت شد")
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isPostingComment = false) }
                    emitEvent(error.toPersianMessage("ثبت کامنت ناموفق بود"))
                }
            )
        }
    }

    private fun emitEvent(message: String) {
        _events.trySend(message)
    }
}
