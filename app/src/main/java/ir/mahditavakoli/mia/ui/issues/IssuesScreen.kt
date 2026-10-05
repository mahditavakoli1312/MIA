package ir.mahditavakoli.mia.ui.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.data.model.RepoIssue

/**
 * All of one project's GitHub issues: open, closed, and the briefs waiting to become issues.
 *
 * @param onOpenIssue navigates to the issue's own screen (body + comments).
 * @param onNewBrief opens the "نیت جدید" screen — a long-form intent for the PO agent, which is
 *        a different thing from the "new issue" sheet this screen already has.
 * @param reloadKey bump it to make this screen re-read GitHub. It is how filing a brief on the
 *        screen above gets the new row to appear here, without that screen having to know
 *        anything about this ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesScreen(
    projectName: String,
    onBack: () -> Unit,
    onOpenIssue: (Int) -> Unit,
    onNewBrief: () -> Unit = {},
    reloadKey: Int = 0,
    // Keyed per project so switching projects gets its own state rather than the previous
    // project's issues flashing up while the new ones load.
    viewModel: IssuesViewModel = viewModel(key = "issues-$projectName")
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(projectName) { viewModel.load(projectName) }
    // 0 is the initial value, which `load` above has already covered.
    LaunchedEffect(reloadKey) { if (reloadKey > 0) viewModel.refresh() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(projectName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = uiState.repoName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onNewBrief) {
                            Icon(
                                imageVector = Icons.Filled.Lightbulb,
                                contentDescription = "نیت جدید",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "بارگذاری دوباره"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = viewModel::onNewIssueClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ایشوی جدید")
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                TabRow(
                    selectedTabIndex = uiState.filter.ordinal,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(
                        selected = uiState.filter == IssueFilter.OPEN,
                        onClick = { viewModel.onFilterChange(IssueFilter.OPEN) },
                        text = { Text("باز (${uiState.openCount})") }
                    )
                    Tab(
                        selected = uiState.filter == IssueFilter.CLOSED,
                        onClick = { viewModel.onFilterChange(IssueFilter.CLOSED) },
                        text = { Text("بسته (${uiState.closedCount})") }
                    )
                    Tab(
                        selected = uiState.filter == IssueFilter.BRIEFS,
                        onClick = { viewModel.onFilterChange(IssueFilter.BRIEFS) },
                        text = { Text("نیت‌ها (${uiState.briefCount})") }
                    )
                }

                Box(Modifier.fillMaxSize()) {
                    when {
                        // A refresh over issues already on screen keeps them visible; only the
                        // very first load takes over the whole screen.
                        uiState.isLoading && uiState.all.issues.isEmpty() ->
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.primary
                            )

                        uiState.errorMessage != null -> ErrorState(
                            message = uiState.errorMessage.orEmpty(),
                            onRetry = viewModel::refresh,
                            modifier = Modifier.align(Alignment.Center)
                        )

                        uiState.visible.isEmpty() -> Text(
                            text = when (uiState.filter) {
                                IssueFilter.OPEN -> "ایشوی بازی در این مخزن نیست."
                                IssueFilter.CLOSED -> "هنوز ایشوی بسته‌شده‌ای نیست."
                                IssueFilter.BRIEFS ->
                                    "هنوز نیتی ثبت نشده. با دکمهٔ 💡 در نوار بالا یک خواستهٔ بزرگ " +
                                        "بنویسید و PO آن را به ایشوهای کوچک می‌شکند."
                            },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // Extra bottom room so the "new issue" FAB never covers the last row.
                            contentPadding = PaddingValues(bottom = 88.dp)
                        ) {
                            items(uiState.visible, key = { it.number }) { issue ->
                                IssueRow(
                                    issue = issue,
                                    isRedoing = issue.number in uiState.redoing,
                                    onClick = { onOpenIssue(issue.number) },
                                    onRedo = { viewModel.onRedo(issue) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                            if (uiState.all.isTruncated) {
                                item {
                                    Text(
                                        text = "فقط تازه‌ترین ایشوها نمایش داده شده‌اند؛ برای " +
                                            "بقیه به گیت‌هاب سر بزنید.",
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        uiState.newIssue?.let { form ->
            NewIssueDialog(
                state = form,
                repoName = uiState.repoName,
                onTitleChange = viewModel::onNewIssueTitleChange,
                onBodyChange = viewModel::onNewIssueBodyChange,
                onToggleLabel = viewModel::onToggleLabel,
                onSubmit = viewModel::submitNewIssue,
                onDismiss = viewModel::dismissNewIssue
            )
        }
    }
}

/**
 * One issue in the list.
 *
 * @param onRedo hands the issue back to the CI agent (the ⟳ button). Offered on every issue,
 *        open or closed: re-doing a closed one is exactly how you ask the agent for another
 *        attempt at something it already merged.
 */
@Composable
private fun IssueRow(
    issue: RepoIssue,
    isRedoing: Boolean,
    onClick: () -> Unit,
    onRedo: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StateDot(isOpen = issue.isOpen)
            Spacer(Modifier.width(10.dp))
            Text(
                text = issue.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRedo, enabled = !isRedoing) {
                if (isRedoing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Replay,
                        contentDescription = "سپردن دوباره به ایجنت",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${issue.number}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            issue.author?.let { author ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatIssueTimestamp(issue.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (issue.commentCount > 0) {
                Text(
                    text = "${issue.commentCount} کامنت",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // The two states where a row is waiting on something the label chips below don't make
        // obvious: QC has sent the work back, or it has given up and nobody is coming.
        val attention = when {
            issue.needsHuman -> "🙋 QC دو بار برگرداند — تا کسی دست به کار نشود، ایجنت سراغش نمی‌رود"
            issue.needsRework -> "🛑 QC اصلاح خواسته — دوباره در صف ایجنت است"
            else -> null
        }
        if (attention != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = attention,
                style = MaterialTheme.typography.labelSmall,
                color = if (issue.needsHuman) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (issue.labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Two labels is all a row fits without pushing the title around; the detail
                // screen shows the rest.
                issue.labels.take(2).forEach { label -> LabelChip(label) }
                if (issue.labels.size > 2) {
                    Text(
                        text = "+${issue.labels.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Green for open, muted for closed — the same at-a-glance cue GitHub's own list uses. */
@Composable
private fun StateDot(isOpen: Boolean) {
    Box(
        Modifier
            .padding(top = 6.dp)
            .size(10.dp)
            .background(
                color = if (isOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = CircleShape
            )
    )
}

@Composable
internal fun LabelChip(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("تلاش دوباره") }
    }
}
