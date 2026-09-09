package ir.mahditavakoli.mia.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.mahditavakoli.mia.data.model.BriefCounts
import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.Task

/**
 * @param canChangeAgentModel false without a GitHub token — there is no repo to repoint, so the
 *        button is hidden rather than shown and then failing on tap.
 * @param onChangeAgentModel opens the picker that repoints this project's repo (@tec/@po/@qc).
 * @param issueSummary this project's open/closed issue counts, or null when GitHub isn't
 *        configured at all — the whole issues strip is then left off the card.
 * @param onOpenIssues opens the full issues list for this project.
 * @param onRetryIssues re-reads the counts after a failed read.
 */
@Composable
fun ProjectCard(
    project: Project,
    modifier: Modifier = Modifier,
    canChangeAgentModel: Boolean = false,
    onChangeAgentModel: () -> Unit = {},
    issueSummary: ProjectIssueSummary? = null,
    onOpenIssues: () -> Unit = {},
    onRetryIssues: () -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (canChangeAgentModel) {
                    IconButton(onClick = onChangeAgentModel) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = "تغییر مدل ایجنت این پروژه",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (issueSummary != null) {
                Spacer(Modifier.height(8.dp))
                IssueStrip(
                    summary = issueSummary,
                    onOpenIssues = onOpenIssues,
                    onRetry = onRetryIssues
                )
            }
            if (project.tasks.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "بدون تسک",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(12.dp))
                project.tasks.forEach { task -> TaskRow(task) }
            }
        }
    }
}

/**
 * The card's GitHub line: how many issues this project's repo has open and closed, and the way
 * into the full list.
 *
 * The three states are kept visibly different on purpose. A repo that failed to read is not the
 * same as one with no issues — a project created before GitHub was configured has no repo at
 * all — and showing "0 / 0" for both would quietly lie about the second case.
 */
@Composable
private fun IssueStrip(
    summary: ProjectIssueSummary,
    onOpenIssues: () -> Unit,
    onRetry: () -> Unit
) {
    val counts = summary.counts
    when {
        counts != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenIssues)
                .padding(vertical = 4.dp)
        ) {
            // A "+" marks a repo with more issues than MIA pages through, so the number reads
            // as "at least this many" rather than as a total that happens to be wrong.
            val more = if (counts.isTruncated) "+" else ""
            CountChip(
                text = "${counts.open}$more باز",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(8.dp))
            CountChip(
                text = "${counts.closed}$more بسته",
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Briefs get their own chip, and only when there are any. They are counted apart
            // from the two above (see IssueList.counts) because a brief is one intent that
            // becomes several issues — adding it to "open" would overstate the queue and hide
            // the number that actually needs someone's attention.
            if (!counts.briefs.isEmpty) {
                Spacer(Modifier.width(8.dp))
                BriefChip(counts.briefs)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "ایشوها",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                // Auto-mirrored: the whole screen runs RTL, so this points into the list.
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        summary.isLoading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "در حال خواندن ایشوها...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary.errorMessage ?: "ایشوهای این مخزن خوانده نشد",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("تلاش دوباره") }
        }
    }
}

/**
 * The card's brief chip: how many intents are open, and where they stand.
 *
 * The decomposition status is in the chip's colour and text rather than a separate row, because
 * there are only three states worth a glance: waiting for the PO, planned, or the PO could not do
 * it. The last one is the only one that needs a human, so it is the one shown in the error colour.
 */
@Composable
private fun BriefChip(briefs: BriefCounts) {
    val (text, container, content) = when {
        briefs.failed > 0 -> Triple(
            "${briefs.failed} نیتِ تجزیه‌نشده",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )

        briefs.pending > 0 -> Triple(
            "${briefs.pending} نیت در انتظار تجزیه",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )

        else -> Triple(
            "${briefs.total} نیتِ تجزیه‌شده",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    CountChip(text = text, container = container, content = content)
}

@Composable
private fun CountChip(text: String, container: Color, content: Color) {
    Surface(color = container, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
    }
}

@Composable
private fun TaskRow(task: Task, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(
            imageVector = if (task.isDone) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (task.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        task.dueDate?.let { dueDate ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = dueDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
