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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
 * All of one project's GitHub issues, split into open and closed tabs.
 *
 * @param onOpenIssue navigates to the issue's own screen (body + comments).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesScreen(
    projectName: String,
    onBack: () -> Unit,
    onOpenIssue: (Int) -> Unit,
    // Keyed per project so switching projects gets its own state rather than the previous
    // project's issues flashing up while the new ones load.
    viewModel: IssuesViewModel = viewModel(key = "issues-$projectName")
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(projectName) { viewModel.load(projectName) }

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
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                TabRow(
                    selectedTabIndex = if (uiState.filter == IssueFilter.OPEN) 0 else 1,
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
                            text = if (uiState.filter == IssueFilter.OPEN) {
                                "ایشوی بازی در این مخزن نیست."
                            } else {
                                "هنوز ایشوی بسته‌شده‌ای نیست."
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
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(uiState.visible, key = { it.number }) { issue ->
                                IssueRow(issue = issue, onClick = { onOpenIssue(issue.number) })
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
    }
}

@Composable
private fun IssueRow(issue: RepoIssue, onClick: () -> Unit) {
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
