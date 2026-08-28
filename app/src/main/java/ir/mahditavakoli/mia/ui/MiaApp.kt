package ir.mahditavakoli.mia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.ui.issues.IssueDetailScreen
import ir.mahditavakoli.mia.ui.issues.IssuesScreen
import ir.mahditavakoli.mia.ui.main.MainScreen
import ir.mahditavakoli.mia.ui.main.MainViewModel

/**
 * The signed-in half of the app and the three screens it moves between: the project list, one
 * project's issues, and one issue.
 *
 * Navigation is two saveable values rather than a nav library. The graph is a straight line
 * (projects → issues → one issue) with no deep links and no arguments beyond a project name and
 * an issue number, so a whole navigation dependency would buy nothing that `rememberSaveable`
 * plus a [BackHandler] doesn't already give — including surviving rotation and process death.
 *
 * [MainViewModel] is held here, above the screens, so returning from an issues screen can
 * refresh that project's counts on the card the user is about to see again.
 */
@Composable
fun MiaApp(
    onLogout: () -> Unit,
    mainViewModel: MainViewModel = viewModel()
) {
    // Non-null: the issues screen for this project is on top of the list.
    var issuesProject by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null: one issue's own screen is on top of that.
    var openIssueNumber by rememberSaveable { mutableStateOf<Int?>(null) }

    val project = issuesProject
    val issueNumber = openIssueNumber

    when {
        project != null && issueNumber != null -> {
            BackHandler { openIssueNumber = null }
            IssueDetailScreen(
                projectName = project,
                issueNumber = issueNumber,
                onBack = { openIssueNumber = null }
            )
        }

        project != null -> {
            // Leaving the issues screen re-reads that project's counts: an issue closed by the
            // CI agent while the user was reading would otherwise leave a stale chip behind.
            val leave = {
                mainViewModel.refreshIssueSummary(project)
                issuesProject = null
            }
            BackHandler { leave() }
            IssuesScreen(
                projectName = project,
                onBack = leave,
                onOpenIssue = { number -> openIssueNumber = number }
            )
        }

        else -> MainScreen(
            onLogout = onLogout,
            viewModel = mainViewModel,
            onOpenIssues = { openedProject -> issuesProject = openedProject.name }
        )
    }
}
