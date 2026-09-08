package ir.mahditavakoli.mia.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.mahditavakoli.mia.network.openrouter.AGENT_MODEL_CHOICES
import ir.mahditavakoli.mia.network.openrouter.AgentModel
import ir.mahditavakoli.mia.network.openrouter.AgentProvider

/**
 * Picks the model this project's GitHub repo runs its AI team (@tec / @po / @qc) on.
 *
 * Confirming rewrites the workflow files in that repo — see
 * [ir.mahditavakoli.mia.data.repository.AgentModelMigrator] — so the dialog is explicit that the
 * change lands on GitHub, not in the app: a user who thinks this is a local preference would be
 * surprised by the commits.
 */
@Composable
fun AgentModelDialog(
    state: AgentModelDialogState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Start on whatever the repo already uses, so the picker opens showing the truth and
    // "confirm" without touching anything is a no-op rather than an accidental change.
    var selected by remember(state.currentModel) {
        mutableStateOf(state.currentModel ?: AGENT_MODEL_CHOICES.first().id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدل ایجنت پروژه") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "مخزن: ${state.repoName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                CurrentModelLine(state)
                Spacer(Modifier.height(12.dp))
                AGENT_MODEL_CHOICES.forEach { model ->
                    ModelRow(
                        model = model,
                        selected = model.id == selected,
                        enabled = !state.isApplying,
                        onSelect = { selected = model.id }
                    )
                }
                // A repo pointing somewhere MIA doesn't offer (hand-edited, or a model since
                // withdrawn) would otherwise vanish from the list with no explanation.
                val unknown = state.currentModel
                    ?.takeIf { current -> AGENT_MODEL_CHOICES.none { it.id == current } }
                if (unknown != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "این مخزن الان روی «$unknown» است که در فهرست بالا نیست؛ انتخاب یک " +
                            "مدل، جایگزینش می‌کند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Picking MiniMax spends real money and needs a key MIA may not hold yet, so
                // say both things before the user commits, not in the failure message after.
                if (AGENT_MODEL_CHOICES.firstOrNull { it.id == selected }?.provider ==
                    AgentProvider.MINIMAX
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "این مدل روی حساب پولی MiniMax شما اجرا می‌شود. کلید MiniMax از " +
                            "تنظیمات خوانده و به‌عنوان سکرت MINIMAX_API_KEY روی همین مخزن ذخیره می‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "با تأیید، فایل‌های ورک‌فلو در همین مخزن گیت‌هاب commit می‌شوند و از " +
                        "اجرای بعدی، ایجنت‌ها با مدل انتخابی کار می‌کنند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = !state.isApplying && !state.isLoadingCurrent
            ) {
                if (state.isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isApplying) "در حال اعمال..." else "تأیید و اعمال روی گیت‌هاب")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isApplying) { Text("انصراف") }
        }
    )
}

@Composable
private fun CurrentModelLine(state: AgentModelDialogState) {
    when {
        state.isLoadingCurrent -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "در حال خواندن مدل فعلی...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.currentModel != null -> Text(
            text = "مدل فعلی: ${state.currentModel}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        else -> Text(
            text = "مدل فعلی خوانده نشد — ممکن است این مخزن ورک‌فلوهای AI را نداشته باشد.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ModelRow(
    model: AgentModel,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = model.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The provider matters as much as the id here: two entries carry the same model
            // name and differ only in whose account (and which Actions secret) pays for it.
            Text(
                text = "${model.provider.label} · ${model.id}",
                style = MaterialTheme.typography.labelSmall,
                color = if (model.provider == AgentProvider.MINIMAX) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
    }
}
