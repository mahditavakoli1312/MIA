package ir.mahditavakoli.mia.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's single prominent voice-control affordance: neon cyan at rest, neon green
 * and pulsing (driven by mic RMS amplitude) while actively listening.
 */
@Composable
fun MicFab(
    recordingState: RecordingState,
    amplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isListening = recordingState is RecordingState.Listening
    val isProcessing = recordingState is RecordingState.Processing

    val pulseScale by animateFloatAsState(
        targetValue = if (isListening) 1f + amplitude * 0.4f else 1f,
        label = "micPulse"
    )
    val activeColor = MaterialTheme.colorScheme.primary // neon green
    val idleColor = MaterialTheme.colorScheme.secondary // neon cyan
    val containerColor = if (isListening) activeColor else idleColor

    Box(modifier = modifier.size(88.dp)) {
        if (isListening) {
            Box(
                Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(activeColor.copy(alpha = 0.22f))
            )
        }
        FloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier.size(64.dp)
        ) {
            when {
                isProcessing -> CircularProgressIndicator(
                    color = Color.Black,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
                isListening -> Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "توقف ضبط صدا",
                    modifier = Modifier.size(32.dp)
                )
                else -> Icon(
                    imageVector = Icons.Filled.MicNone,
                    contentDescription = "شروع ضبط صدا",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
