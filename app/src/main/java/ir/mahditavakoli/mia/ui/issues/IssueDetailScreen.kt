package ir.mahditavakoli.mia.ui.issues

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.data.model.IssueComment
import ir.mahditavakoli.mia.data.model.RepoIssue

/**
 * One issue in full: title, state, body, the whole comment thread, and a box to add a comment.
 *
 * The comment box lives in the bottom bar (and the scaffold lifts with the keyboard) so writing
 * a reply works the same way as giving MIA a command on the main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailScreen(
    projectName: String,
    issueNumber: Int,
    onBack: () -> Unit,
    // Keyed per issue: two issues open in sequence must not share one screen's state.
    viewModel: IssueDetailViewModel = viewModel(key = "issue-$projectName-$issueNumber")
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(projectName, issueNumber) { viewModel.load(projectName, issueNumber) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text("ایشو #$issueNumber") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    },
                    actions = {
                        // "Re-do": comment "@tec do this : …" and let the agent queue pick the
                        // issue up again. Only once the issue has loaded — the comment is built
                        // from its body, which isn't in hand before that.
                        if (uiState.issue != null) {
                            IconButton(
                                onClick = viewModel::onRedo,
                                enabled = !uiState.isPostingComment
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Replay,
                                    contentDescription = "سپردن دوباره به ایجنت",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        // Everything MIA cannot do here — closing the issue, editing it,
                        // reacting — is one tap away on GitHub itself.
                        val htmlUrl = uiState.issue?.htmlUrl.orEmpty()
                        if (htmlUrl.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, htmlUrl.toUri())
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        // A device with no browser at all: nothing to do but
                                        // leave the user where they are.
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "باز کردن در گیت‌هاب"
                                )
                            }
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
            bottomBar = {
                // Only offered once the issue itself loaded: commenting on an issue that failed
                // to read would just fail again at the API.
                if (uiState.issue != null) {
                    CommentComposer(
                        draft = uiState.commentDraft,
                        canPost = uiState.canPostComment,
                        isPosting = uiState.isPostingComment,
                        onDraftChange = viewModel::onCommentDraftChange,
                        onPost = viewModel::onPostComment
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val issue = uiState.issue
                when {
                    uiState.isLoading && issue == null -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )

                    uiState.errorMessage != null -> ErrorState(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = viewModel::refresh,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    issue != null -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        item { IssueHeader(issue) }
                        item {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "کامنت‌ها (${uiState.comments.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (uiState.comments.isEmpty()) {
                            item {
                                Text(
                                    text = "هنوز کامنتی ثبت نشده است.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(uiState.comments, key = { it.id }) { comment ->
                            CommentCard(comment)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueHeader(issue: RepoIssue) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(isOpen = issue.isOpen)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "#${issue.number}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = issue.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOfNotNull(issue.author, formatIssueTimestamp(issue.createdAt).ifEmpty { null })
                .joinToString(" • "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (issue.labels.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                issue.labels.forEach { label -> LabelChip(label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                // Issue bodies are Markdown. MIA renders them as plain text rather than
                // half-parsing them: the agent's briefs are mostly prose and lists, which stay
                // readable, and a wrong parse would hide content the user needs to act on.
                text = issue.body?.takeIf { it.isNotBlank() } ?: "این ایشو توضیحی ندارد.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatePill(isOpen: Boolean) {
    Surface(
        color = if (isOpen) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = if (isOpen) "باز" else "بسته",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isOpen) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun CommentCard(comment: IssueComment) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author ?: "ناشناس",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatIssueTimestamp(comment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = comment.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** The write half of the screen: type a comment, send it to GitHub. */
@Composable
private fun CommentComposer(
    draft: String,
    canPost: Boolean,
    isPosting: Boolean,
    onDraftChange: (String) -> Unit,
    onPost: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 140.dp),
                placeholder = { Text("کامنت خود را بنویسید…") },
                shape = RoundedCornerShape(24.dp),
                enabled = !isPosting,
                // No ImeAction.Send here: comments are often multi-line, and Enter should add a
                // line rather than post half a thought.
                maxLines = 4
            )
            FilledIconButton(
                onClick = {
                    keyboard?.hide()
                    onPost()
                },
                enabled = canPost,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = Color.Black
                )
            ) {
                if (isPosting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "ثبت کامنت"
                    )
                }
            }
        }
    }
}
