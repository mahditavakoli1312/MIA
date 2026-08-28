package ir.mahditavakoli.mia.ui.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.mahditavakoli.mia.data.repository.GitHubRepository

/**
 * Writes a new issue into this project's GitHub repo.
 *
 * The agent label gets a switch of its own, apart from the other labels, because it is the one
 * choice on this sheet that *does* something: an issue opened with `by-agent` is picked up by
 * the repo's workflow and worked on within minutes. Left as one chip among many it would read
 * as filing metadata, and users would trigger — or fail to trigger — a real agent run by
 * accident.
 */
@Composable
fun NewIssueDialog(
    state: NewIssueFormState,
    repoName: String,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onToggleLabel: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ایشوی جدید") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "مخزن: $repoName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("عنوان") },
                    singleLine = true,
                    enabled = !state.isSubmitting
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.body,
                    onValueChange = onBodyChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 180.dp),
                    label = { Text("توضیحات (اختیاری)") },
                    // This body is the brief the agent works from, so it needs room to be a
                    // few real sentences rather than a one-line summary.
                    minLines = 3,
                    enabled = !state.isSubmitting
                )
                Spacer(Modifier.height(12.dp))
                AgentSwitch(
                    checked = state.agentHandled,
                    enabled = !state.isSubmitting,
                    onCheckedChange = { onToggleLabel(GitHubRepository.AGENT_LABEL) }
                )
                Spacer(Modifier.height(12.dp))
                OtherLabels(
                    state = state,
                    onToggleLabel = onToggleLabel
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = state.canSubmit) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isSubmitting) "در حال ثبت..." else "ثبت ایشو")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSubmitting) { Text("انصراف") }
        }
    )
}

@Composable
private fun AgentSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "سپردن به ایجنت (${GitHubRepository.AGENT_LABEL})",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (checked) {
                    "با ثبت این ایشو، ورک‌فلوی ایجنت در گیت‌هاب اجرا می‌شود و روی همین ایشو کار می‌کند."
                } else {
                    "ایشو فقط ثبت می‌شود و ایجنت سراغش نمی‌رود."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherLabels(
    state: NewIssueFormState,
    onToggleLabel: (String) -> Unit
) {
    // by-agent has its own switch above; showing it here as well would let the two disagree
    // on screen even though they write to the same set.
    val labels = state.availableLabels.filter { it != GitHubRepository.AGENT_LABEL }
    Text(
        text = "برچسب‌های دیگر",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(4.dp))
    when {
        state.isLoadingLabels -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "در حال خواندن برچسب‌های مخزن...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        labels.isEmpty() -> Text(
            text = "این مخزن برچسب دیگری ندارد.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { label ->
                FilterChip(
                    selected = label in state.selectedLabels,
                    onClick = { onToggleLabel(label) },
                    enabled = !state.isSubmitting,
                    label = { Text(label) }
                )
            }
        }
    }
}
