package ir.mahditavakoli.mia.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The command timeline: one row per step, from understanding the command to a merged pull request.
 *
 * It replaces a three-line status banner that went quiet the moment the app's own work was done —
 * which was exactly when the interesting part started. Everything below [CommandStage.EXECUTING]
 * is happening on GitHub, minutes later, and this is where the user watches it.
 *
 * @param onOpenArtefact opens a finished step's artefact in the browser (the issue, the workflow
 *        run, the pull request).
 * @param onRetry re-queues the issue after a failure. Shown only on a failed timeline.
 */
@Composable
fun CommandTimelineCard(
    timeline: CommandTimeline,
    now: Long,
    onToggleExpanded: () -> Unit,
    onOpenArtefact: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SummaryRow(
                timeline = timeline,
                onToggleExpanded = onToggleExpanded,
                onDismiss = onDismiss
            )
            AnimatedVisibility(visible = timeline.isExpanded) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    timeline.steps.forEachIndexed { index, step ->
                        StepRow(
                            step = step,
                            now = now,
                            isLast = index == timeline.steps.lastIndex,
                            onOpenArtefact = onOpenArtefact
                        )
                    }
                    if (timeline.hasFailed && timeline.issue != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Filled.Replay,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("تلاش دوباره")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one row that is always visible, and the whole card once everything has finished.
 *
 * A finished command collapses to this: the timeline has nothing left to say, and leaving six rows
 * of ticks on screen would push the project list off it.
 */
@Composable
private fun SummaryRow(
    timeline: CommandTimeline,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StateBadge(
            state = when {
                timeline.hasFailed -> StepState.FAILED
                timeline.isFinished -> StepState.DONE
                else -> StepState.RUNNING
            }
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = timeline.summary,
                style = MaterialTheme.typography.labelLarge,
                color = if (timeline.hasFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            timeline.totalTokens?.let { tokens ->
                Text(
                    text = "${formatTokens(tokens)} توکن",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = if (timeline.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (timeline.isExpanded) "بستن جزئیات" else "نمایش جزئیات",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        // Dismissing is only offered once nothing is in flight: a card the user closes mid-run
        // would take the only view of a live agent run with it.
        if (timeline.isFinished) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "بستن",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun StepRow(
    step: TimelineStep,
    now: Long,
    isLast: Boolean,
    onOpenArtefact: (String) -> Unit
) {
    val url = step.artefactUrl
    // Only a finished step's artefact exists to open. A pending row that reacted to a tap and did
    // nothing would read as a broken button.
    val openable = url != null && (step.state == StepState.DONE || step.state == StepState.FAILED)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable { onOpenArtefact(url!!) } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StateBadge(step.state)
            // The connector, drawn per row rather than as one line behind them, so it stops at
            // the last step instead of trailing off the bottom of the card.
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(2.dp)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = CommandTimeline.stageLabel(step.stage),
                style = MaterialTheme.typography.bodyMedium,
                color = when (step.state) {
                    StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    StepState.FAILED -> MaterialTheme.colorScheme.error
                    StepState.RUNNING -> MaterialTheme.colorScheme.primary
                    StepState.DONE -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            step.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        step.tokens?.let { tokens ->
            Text(
                text = "${formatTokens(tokens)} توکن",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(8.dp))
        }
        step.elapsedMs(now)?.let { elapsed ->
            Text(
                text = formatElapsed(elapsed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (openable) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = "باز کردن",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * The dot at the start of a row: a tick, a pulsing neon ring, an empty outline, or a cross.
 *
 * The pulse is what distinguishes "waiting on a robot that is working" from "stuck", which on a
 * step that legitimately takes four minutes is the difference between patience and a force-quit.
 */
@Composable
private fun StateBadge(state: StepState) {
    val size = 18.dp
    when (state) {
        StepState.DONE -> Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp)
            )
        }

        StepState.FAILED -> Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(12.dp)
            )
        }

        StepState.RUNNING -> {
            val transition = rememberInfiniteTransition(label = "step-pulse")
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "step-pulse-alpha"
            )
            Box(
                Modifier
                    .size(size)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }

        StepState.PENDING -> Box(
            Modifier
                .size(size)
                .background(Color.Transparent, CircleShape)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

/** "۲م ۱۴ث" is unreadable at a glance; Locale.US digits match the rest of the app's numbers. */
private fun formatElapsed(ms: Long): String {
    val seconds = (ms / 1000.0).roundToInt().coerceAtLeast(0)
    if (seconds < 60) return String.format(Locale.US, "%ds", seconds)
    val minutes = seconds / 60
    if (minutes < 60) return String.format(Locale.US, "%d:%02d", minutes, seconds % 60)
    return String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
}

private fun formatTokens(tokens: Int): String = String.format(Locale.US, "%,d", tokens)
