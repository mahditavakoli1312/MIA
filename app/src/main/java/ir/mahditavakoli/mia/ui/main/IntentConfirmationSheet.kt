package ir.mahditavakoli.mia.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mahditavakoli.mia.data.model.ActionType

/**
 * The last stop before a command runs: everything MIA understood, one row per intent, editable.
 *
 * The sheet exists because the two model calls in front of it are probabilistic and the actions
 * behind it are not. An add_task the model got slightly wrong is a bad issue title; a
 * delete_project it got wrong is gone, along with every task under it (the schema cascades). So
 * destructive rows are coloured with the error role and carry their own checkbox — the primary
 * button stays disabled until each one is ticked, which makes "run" a deliberate act rather than
 * a reflex on a sheet the user has stopped reading.
 *
 * All state lives in [MainViewModel]; this composable only renders it and reports edits, which
 * is what lets a half-corrected batch survive rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentConfirmationSheet(
    state: IntentConfirmation,
    onTitleChange: (Int, String) -> Unit,
    onDueDateChange: (Int, String) -> Unit,
    onAcknowledgeChange: (Int, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        // A swipe-away is the same decision as "اصلاح می‌کنم" — nothing runs, the text comes back.
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "این‌طور متوجه شدم",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "قبل از اجرا می‌توانید هر مورد را اصلاح کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Capped rather than free-growing: a command that split into a dozen issues must not
            // push the two action buttons off the bottom of the sheet.
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.rows, key = { it.id }) { row ->
                    IntentRow(
                        row = row,
                        enabled = !state.isExecuting,
                        onTitleChange = { onTitleChange(row.id, it) },
                        onDueDateChange = { onDueDateChange(row.id, it) },
                        onAcknowledgeChange = { onAcknowledgeChange(row.id, it) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !state.isExecuting) {
                    Text("اصلاح می‌کنم")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = state.canExecute) {
                    if (state.isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("انجام بده")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IntentRow(
    row: ConfirmableIntent,
    enabled: Boolean,
    onTitleChange: (String) -> Unit,
    onDueDateChange: (String) -> Unit,
    onAcknowledgeChange: (Boolean) -> Unit
) {
    val accent =
        if (row.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (row.isDestructive) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = row.intent.actionType.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.intent.actionType.persianLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent
                    )
                    Text(
                        text = "پروژه: ${row.intent.projectName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Project-level actions have no task to edit; showing empty fields for them would
            // invite the user to fill in something the intent has nowhere to put.
            if (row.intent.actionType.hasTask) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = row.intent.taskTitle.orEmpty(),
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("عنوان تسک") },
                    isError = row.intent.taskTitle.isNullOrBlank()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = row.intent.dueDate.orEmpty(),
                    onValueChange = onDueDateChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("مهلت (YYYY-MM-DD)") },
                    placeholder = { Text("بدون مهلت") },
                    leadingIcon = {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    },
                    // A date the user typed by hand is the one field here that can be malformed
                    // in a way Supabase rejects, so say so on the row rather than at execution.
                    isError = !row.intent.dueDate.isNullOrBlank() && !row.intent.dueDate.isIsoDate()
                )
            }

            if (row.isDestructive) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = row.isAcknowledged,
                        onCheckedChange = onAcknowledgeChange,
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(checkedColor = accent)
                    )
                    Text(
                        text = if (row.intent.actionType == ActionType.DELETE_PROJECT) {
                            "می‌دانم این پروژه و همهٔ تسک‌هایش حذف می‌شود"
                        } else {
                            "می‌دانم این تسک حذف می‌شود"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Loose on purpose: this only decides whether to tint a field, not whether to send it. */
private fun String.isIsoDate(): Boolean = Regex("""\d{4}-\d{2}-\d{2}""").matches(this)

/** Whether this action names a task the user could correct. */
private val ActionType.hasTask: Boolean
    get() = this != ActionType.CREATE_PROJECT && this != ActionType.DELETE_PROJECT

private val ActionType.persianLabel: String
    get() = when (this) {
        ActionType.CREATE_PROJECT -> "ساخت پروژه"
        ActionType.DELETE_PROJECT -> "حذف پروژه"
        ActionType.ADD_TASK -> "افزودن تسک"
        ActionType.REMOVE_TASK -> "حذف تسک"
        ActionType.COMPLETE_TASK -> "بستن تسک"
        ActionType.REOPEN_TASK -> "بازکردن تسک"
        ActionType.SET_DUE_DATE -> "تنظیم مهلت"
    }

private val ActionType.icon: ImageVector
    get() = when (this) {
        ActionType.CREATE_PROJECT -> Icons.Filled.CreateNewFolder
        ActionType.DELETE_PROJECT -> Icons.Filled.FolderOff
        ActionType.ADD_TASK -> Icons.Filled.PlaylistAdd
        ActionType.REMOVE_TASK -> Icons.Filled.DeleteForever
        ActionType.COMPLETE_TASK -> Icons.Filled.CheckCircle
        ActionType.REOPEN_TASK -> Icons.Filled.Undo
        ActionType.SET_DUE_DATE -> Icons.Filled.CalendarMonth
    }
