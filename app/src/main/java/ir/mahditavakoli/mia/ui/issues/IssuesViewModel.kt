package ir.mahditavakoli.mia.ui.issues

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.IssueList
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.toPersianMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which half of a project's issues the list is showing. */
enum class IssueFilter { OPEN, CLOSED }

/**
 * The "open a new issue" sheet, non-null while it is showing.
 *
 * [selectedLabels] holds every label including [GitHubRepository.AGENT_LABEL] — the switch that
 * hands the issue to the CI agent is a different presentation of the same set, not a second
 * source of truth, so what the sheet shows and what GitHub receives can never disagree.
 */
data class NewIssueFormState(
    val title: String = "",
    val body: String = "",
    val selectedLabels: Set<String> = emptySet(),
    /** Labels the repo defines, read when the sheet opens. */
    val availableLabels: List<String> = emptyList(),
    val isLoadingLabels: Boolean = true,
    val isSubmitting: Boolean = false
) {
    val agentHandled: Boolean get() = GitHubRepository.AGENT_LABEL in selectedLabels

    /** A title is the one thing GitHub requires; the body and labels are optional. */
    val canSubmit: Boolean get() = title.isNotBlank() && !isSubmitting
}

data class IssuesUiState(
    val projectName: String = "",
    val repoName: String = "",
    val isLoading: Boolean = true,
    val all: IssueList = IssueList.EMPTY,
    val filter: IssueFilter = IssueFilter.OPEN,
    val errorMessage: String? = null,
    /** Non-null while the new-issue sheet is open. */
    val newIssue: NewIssueFormState? = null,
    /**
     * Issue numbers whose Re-do comment is still in flight — a set rather than a flag so two
     * rows can be re-done in a row without the first one's spinner following the second.
     */
    val redoing: Set<Int> = emptySet()
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

    // One-shot snackbar messages, same Channel pattern as the other ViewModels.
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

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

    // Opening an issue --------------------------------------------------------

    /**
     * Opens the new-issue sheet and starts reading the repo's labels.
     *
     * The agent label starts selected or not according to the same Settings toggle that decides
     * whether spoken tasks are handed to the agent — a user who has turned the CI agent off
     * shouldn't have to un-tick it on every issue they write.
     */
    fun onNewIssueClick() {
        val projectName = _uiState.value.projectName
        if (projectName.isEmpty()) return
        _uiState.update {
            it.copy(
                newIssue = NewIssueFormState(
                    selectedLabels = if (NetworkModule.secretStore.agentHandledByDefault) {
                        setOf(GitHubRepository.AGENT_LABEL)
                    } else {
                        emptySet()
                    }
                )
            )
        }
        viewModelScope.launch {
            val labels = gitHubRepository.labelsFor(projectName)
                // A repo whose labels can't be read still gets the one label that matters;
                // GitHub creates it on demand when the issue is opened.
                .getOrDefault(listOf(GitHubRepository.AGENT_LABEL))
            // Ignore a late answer for a sheet the user already closed.
            updateForm { it.copy(availableLabels = labels, isLoadingLabels = false) }
        }
    }

    fun dismissNewIssue() {
        // Never pull the sheet away mid-write; the submit path closes it when GitHub answers.
        if (_uiState.value.newIssue?.isSubmitting == true) return
        _uiState.update { it.copy(newIssue = null) }
    }

    fun onNewIssueTitleChange(value: String) = updateForm { it.copy(title = value) }

    fun onNewIssueBodyChange(value: String) = updateForm { it.copy(body = value) }

    fun onToggleLabel(label: String) = updateForm { form ->
        form.copy(
            selectedLabels = if (label in form.selectedLabels) {
                form.selectedLabels - label
            } else {
                form.selectedLabels + label
            }
        )
    }

    /**
     * Opens the issue on GitHub and drops it straight into the list.
     *
     * The new issue is inserted rather than triggering a re-read: it is always open and always
     * newest, so its place is known, and a full refresh would cost a second round trip to show
     * something already in hand.
     */
    fun submitNewIssue() {
        val state = _uiState.value
        val form = state.newIssue ?: return
        if (!form.canSubmit) return
        updateForm { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            gitHubRepository.createIssue(
                projectName = state.projectName,
                title = form.title,
                body = form.body,
                labels = form.selectedLabels.toList()
            ).fold(
                onSuccess = { issue ->
                    _uiState.update {
                        it.copy(
                            newIssue = null,
                            all = it.all.copy(issues = listOf(issue) + it.all.issues),
                            // A new issue is open, so show the tab it landed in.
                            filter = IssueFilter.OPEN
                        )
                    }
                    _events.trySend(
                        if (form.agentHandled) {
                            "ایشو #${issue.number} ثبت شد و به ایجنت سپرده شد"
                        } else {
                            "ایشو #${issue.number} ثبت شد"
                        }
                    )
                },
                onFailure = { error ->
                    updateForm { it.copy(isSubmitting = false) }
                    _events.trySend(error.toPersianMessage("ثبت ایشو ناموفق بود"))
                }
            )
        }
    }

    // Handing an issue back to the agent -------------------------------------

    /**
     * "Re-do": comments `@tec do this : …` on [issue], which puts it back in the agent's queue
     * (see [GitHubRepository.redoIssue]).
     *
     * The list is left as it is afterwards. The label the workflow attaches lands on GitHub a
     * moment later, so re-drawing the row from what the app knows now would only show a state
     * that is already stale; the user pulls to refresh, or opens the issue, to watch it move.
     */
    fun onRedo(issue: RepoIssue) {
        val state = _uiState.value
        if (state.projectName.isEmpty() || issue.number in state.redoing) return
        _uiState.update { it.copy(redoing = it.redoing + issue.number) }
        viewModelScope.launch {
            gitHubRepository.redoIssue(state.projectName, issue).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            redoing = it.redoing - issue.number,
                            all = it.all.copy(
                                issues = it.all.issues.map { row ->
                                    if (row.number == issue.number) {
                                        row.copy(commentCount = row.commentCount + 1)
                                    } else {
                                        row
                                    }
                                }
                            )
                        )
                    }
                    _events.trySend("ایشو #${issue.number} دوباره به ایجنت سپرده شد")
                },
                onFailure = { error ->
                    _uiState.update { it.copy(redoing = it.redoing - issue.number) }
                    _events.trySend(error.toPersianMessage("سپردن دوباره به ایجنت ناموفق بود"))
                }
            )
        }
    }

    private fun updateForm(transform: (NewIssueFormState) -> NewIssueFormState) {
        _uiState.update { state ->
            val form = state.newIssue ?: return@update state
            state.copy(newIssue = transform(form))
        }
    }
}
