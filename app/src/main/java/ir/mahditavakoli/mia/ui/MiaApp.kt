package ir.mahditavakoli.mia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.ui.brief.NewBriefScreen
import ir.mahditavakoli.mia.ui.issues.IssueDetailScreen
import ir.mahditavakoli.mia.ui.issues.IssuesScreen
import ir.mahditavakoli.mia.ui.main.MainScreen
import ir.mahditavakoli.mia.ui.main.MainViewModel
import ir.mahditavakoli.mia.ui.spend.SpendScreen

/** One issue, named the way a notification can carry it: by project name and issue number. */
data class IssueDeepLink(val projectName: String, val issueNumber: Int)

/**
 * The signed-in half of the app and the five screens it moves between: the project list, one
 * project's issues, one issue, the form for a new brief, and the token-spend report.
 *
 * Navigation is four saveable values rather than a nav library. The graph is a straight line
 * (projects → issues → one issue, with the new-brief screen as a leaf off the issues list and the
 * spend screen as a leaf off the project list) with one entry point from outside ([deepLink], from
 * a notification) and no arguments beyond a project name and an issue number, so a whole navigation
 * dependency would buy nothing that `rememberSaveable` plus a [BackHandler] doesn't already give —
 * including surviving rotation and process death.
 *
 * [MainViewModel] is held here, above the screens, so returning from an issues screen can
 * refresh that project's counts on the card the user is about to see again.
 *
 * @param deepLink an issue a tapped notification asked for. Non-null jumps straight to that
 *        issue's screen, with the project's issues list behind it so Back lands somewhere sane
 *        rather than closing the app.
 */
@Composable
fun MiaApp(
    onLogout: () -> Unit,
    mainViewModel: MainViewModel = viewModel(),
    deepLink: IssueDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    // Non-null: the issues screen for this project is on top of the list.
    var issuesProject by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null: one issue's own screen is on top of that.
    var openIssueNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    // True: the "new brief" screen is on top of the issues list. A flag rather than a project
    // name because it is only ever reachable from that project's own issues screen.
    var writingBrief by rememberSaveable { mutableStateOf(false) }
    // True: the spend screen is on top of the project list. It hangs off the list rather than off
    // a project because the report it shows spans every project at once.
    var showingSpend by rememberSaveable { mutableStateOf(false) }
    // Bumped when a brief is filed, which is what makes the issues list behind re-read GitHub
    // and show it. A counter rather than a boolean: two briefs in a row must each trigger it.
    var issuesReloadKey by rememberSaveable { mutableStateOf(0) }

    // Consumed once: the caller clears it, so returning to the list doesn't bounce the user
    // straight back into the issue they just navigated away from.
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        issuesProject = link.projectName
        openIssueNumber = link.issueNumber
        // A tapped notification is about one issue, so it takes the user out of the spend report
        // rather than opening the issue behind it.
        showingSpend = false
        onDeepLinkHandled()
    }

    val project = issuesProject
    val issueNumber = openIssueNumber

    when {
        showingSpend -> {
            BackHandler { showingSpend = false }
            SpendScreen(onBack = { showingSpend = false })
        }

        project != null && writingBrief -> {
            BackHandler { writingBrief = false }
            NewBriefScreen(
                projectName = project,
                onBack = { writingBrief = false },
                // Straight back to the issues list, which re-reads and shows the new brief in
                // the place the PO's plan will appear under.
                onFiled = {
                    writingBrief = false
                    issuesReloadKey += 1
                }
            )
        }

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
                onOpenIssue = { number -> openIssueNumber = number },
                onNewBrief = { writingBrief = true },
                reloadKey = issuesReloadKey
            )
        }

        else -> MainScreen(
            onLogout = onLogout,
            viewModel = mainViewModel,
            onOpenIssues = { openedProject -> issuesProject = openedProject.name },
            onOpenSpend = { showingSpend = true }
        )
    }
}
