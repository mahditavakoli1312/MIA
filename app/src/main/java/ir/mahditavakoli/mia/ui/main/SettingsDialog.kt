package ir.mahditavakoli.mia.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Minimal settings surface: the Gemini API key (stored encrypted, pushed to each repo's
 * Actions secrets) and whether new voice-created tasks are agent-handled by default.
 */
@Composable
fun SettingsDialog(
    agentHandledByDefault: Boolean,
    geminiApiKey: String,
    onAgentHandledChange: (Boolean) -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onSaveGeminiApiKey: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("سپردن تسک‌های جدید به ایجنت")
                    Switch(checked = agentHandledByDefault, onCheckedChange = onAgentHandledChange)
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = geminiApiKey,
                    onValueChange = onGeminiApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("کلید API جمینای") },
                    visualTransformation = PasswordVisualTransformation()
                )
                TextButton(
                    onClick = onSaveGeminiApiKey,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("ذخیره کلید") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}
