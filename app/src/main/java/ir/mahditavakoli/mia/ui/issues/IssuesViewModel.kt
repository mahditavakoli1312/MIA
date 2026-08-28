package ir.mahditavakoli.mia.ui.issues

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.IssueList
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.toPersianMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which half of a project's issues the list is showing. */
enum class IssueFilter { OPEN, CLOSED }

data class IssuesUiState(
    val projectName: String = "",
    val repoName: String = "",
    val isLoading: Boolean = true,
    val all: IssueList = IssueList.EMPTY,
    val filter: IssueFilter = IssueFilter.OPEN,
    val errorMessage: String? = null
) {
    /** The issues the selected tab shows, newest first (GitHub's own list order). */
    val visible: List<RepoIssue> get() = all.withState(open = filter == IssueFilter.OPEN)

    val openCount: Int get() = all.counts.open
    val closedCount: Int get() = all.counts.closed
}

/**
 * Backs the list of one project's GitHub issues.
 *
 * Open and closed come from a single read (see [GitHubRepository.issuesFor]) and the tabs only
 * filter what is already in memory, so switching tabs is instant and costs no request. [load] is
 * idempotent per project, which lets the screen call it from a `LaunchedEffect` without
 * re-fetching on every recomposition or rotation.
 */
class IssuesViewModel(application: Application) : AndroidViewModel(application) {

    private val gitHubRepository = NetworkModule.gitHubRepository

    private val _uiState = MutableStateFlow(IssuesUiState())
    val uiState: StateFlow<IssuesUiState> = _uiState.asStateFlow()

    private var loadedProject: String? = null

    fun load(projectName: String) {
        if (loadedProject == projectName) return
        loadedProject = projectName
        _uiState.update {
            it.copy(
                projectName = projectName,
                repoName = GitHubRepository.repoNameFor(projectName)
            )
        }
        refresh()
    }

    fun onFilterChange(filter: IssueFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun refresh() {
        val projectName = _uiState.value.projectName
        if (projectName.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            gitHubRepository.issuesFor(projectName).fold(
                onSuccess = { list ->
                    _uiState.update { it.copy(all = list, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.toPersianMessage("خواندن ایشوهای این مخزن ناموفق بود")
                        )
                    }
                }
            )
        }
    }
}
