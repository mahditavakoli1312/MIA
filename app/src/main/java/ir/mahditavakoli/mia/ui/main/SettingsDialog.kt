package ir.mahditavakoli.mia.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * Minimal settings surface (all stored encrypted): the Gemini API key used for voice→intent,
 * the OpenRouter API key — which now powers both typed commands in the app
 * (`minimax/minimax-m3:free`) and the CI agent, as each repo's `OPENROUTER_API_KEY` Actions
 * secret — an optional spare
 * OpenRouter key that takes over when the primary one hits its free-tier limit, and whether new
 * tasks are agent-handled by default.
 */
@Composable
fun SettingsDialog(
    agentHandledByDefault: Boolean,
    geminiApiKey: String,
    openRouterApiKey: String,
    openRouterFallbackApiKey: String,
    onAgentHandledChange: (Boolean) -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onSaveGeminiApiKey: () -> Unit,
    onOpenRouterApiKeyChange: (String) -> Unit,
    onSaveOpenRouterApiKey: () -> Unit,
    onOpenRouterFallbackApiKeyChange: (String) -> Unit,
    onSaveOpenRouterFallbackApiKey: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات") },
        text = {
            // Four controls plus supporting text overflow a short screen; without this the
            // bottom key field and its save button are simply unreachable.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                    label = { Text("کلید API جمینای (دستور صوتی)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                TextButton(
                    onClick = onSaveGeminiApiKey,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("ذخیره کلید") }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = openRouterApiKey,
                    onValueChange = onOpenRouterApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("کلید API اوپن‌روتر (دستور متنی و ایجنت)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                TextButton(
                    onClick = onSaveOpenRouterApiKey,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("ذخیره کلید") }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = openRouterFallbackApiKey,
                    onValueChange = onOpenRouterFallbackApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("کلید پشتیبان اوپن‌روتر (هنگام اتمام سهمیه)") },
                    supportingText = {
                        Text("اگر کلید اصلی به محدودیت بخورد (۴۲۹) یا اعتبارش تمام شود، خودکار از این کلید استفاده می‌شود.")
                    },
                    visualTransformation = PasswordVisualTransformation()
                )
                TextButton(
                    onClick = onSaveOpenRouterFallbackApiKey,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("ذخیره کلید") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}
