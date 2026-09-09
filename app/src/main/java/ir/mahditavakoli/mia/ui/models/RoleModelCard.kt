package ir.mahditavakoli.mia.ui.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mahditavakoli.mia.network.openrouter.AGENT_MODEL_CHOICES
import ir.mahditavakoli.mia.network.openrouter.AgentModel
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull

/**
 * One role, the model it is on, and — once opened — the list it can be moved to.
 *
 * Collapsed by default, and that is the whole reason this is a card rather than five radio
 * groups stacked on one screen: with five roles and seven models, an always-open screen is
 * thirty-five rows deep and the answer to "what is my QC on?" is buried in the middle of it.
 * Collapsed, the screen is five lines that each say a role and its model, which is the question
 * a user actually opens this screen with.
 *
 * Used by both model screens — a project's team and the defaults for new projects — so the two
 * cannot drift into describing the same choice differently.
 *
 * @param selected the model this role is on, or null when it could not be read (an older repo
 *        missing that workflow). Null is shown as such rather than as the first row silently
 *        preselected, since "we don't know" and "it is on this" want different actions.
 * @param dirty true when [selected] is a pending change that has not been applied yet.
 * @param footnote one line under the role's own note — where a screen says something specific
 *        about this role, like the app model taking effect immediately.
 */
@Composable
fun RoleModelCard(
    role: AgentRole,
    selected: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    dirty: Boolean = false,
    footnote: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = role.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // A dot rather than a word: the row is already dense, and the "apply"
                    // button at the bottom is what explains what the dot is waiting for.
                    if (dirty) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "●",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    text = role.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                CurrentModelLine(selected = selected, isLoading = isLoading)
                if (footnote != null) {
                    Text(
                        text = footnote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "بستن فهرست مدل‌ها" else "انتخاب مدل",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                AGENT_MODEL_CHOICES.forEach { model ->
                    ModelRow(
                        model = model,
                        selected = model.id == selected,
                        enabled = enabled,
                        onSelect = { onSelect(model.id) }
                    )
                }
                // A repo pointing somewhere MIA doesn't offer (hand-edited on GitHub, or a model
                // since withdrawn) would otherwise vanish from the list with no explanation.
                if (selected != null && agentModelOrNull(selected) == null) {
                    Text(
                        text = "این نقش الان روی «$selected» است که در فهرست بالا نیست؛ " +
                            "انتخاب یک مدل، جایگزینش می‌کند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentModelLine(selected: String?, isLoading: Boolean) {
    when {
        isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
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

        selected == null -> Text(
            text = "مدل فعلی خوانده نشد — این مخزن ورک‌فلوی این نقش را ندارد.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        else -> {
            val model = agentModelOrNull(selected)
            Text(
                text = model?.label ?: selected,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            // Two entries in the list carry the same model name and differ only in whose
            // account pays for it, so the provider belongs next to every answer.
            if (model != null) {
                Text(
                    text = "${model.provider.label} · ${model.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = providerColor(model.provider)
                )
            }
        }
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
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = model.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${model.provider.label} · ${model.id}",
                style = MaterialTheme.typography.labelSmall,
                color = providerColor(model.provider)
            )
        }
    }
}

/** MiniMax spends the user's own balance, so it is coloured apart from the free entries. */
@Composable
private fun providerColor(provider: AgentProvider) =
    if (provider == AgentProvider.MINIMAX) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.secondary
    }
