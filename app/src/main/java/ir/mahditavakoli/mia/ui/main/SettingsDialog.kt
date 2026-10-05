package ir.mahditavakoli.mia.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
 * the OpenRouter API key — which powers both typed commands in the app and the CI agent, as each
 * repo's `OPENROUTER_API_KEY` Actions secret — an optional spare OpenRouter key that takes over
 * when the primary one hits its free-tier limit, the MiniMax platform key for the paid
 * `MiniMax-M3` option, whether new tasks are agent-handled by default, and whether an understood
 * command is shown for approval before it runs.
 *
 * Choosing *models* is deliberately not here. It used to be — one picker for the app's own
 * commands, buried between two key fields — but a model per role does not fit in a dialog, and
 * splitting "the app's model" from "every other role's model" across two surfaces is how a user
 * ends up changing one believing they changed the other. Both live on the model screens
 * ([ir.mahditavakoli.mia.ui.models.DefaultModelsScreen] and
 * [ir.mahditavakoli.mia.ui.models.ProjectModelsScreen]) now, and this dialog says where.
 */
@Composable
fun SettingsDialog(
    agentHandledByDefault: Boolean,
    confirmBeforeExecute: Boolean,
    geminiApiKey: String,
    openRouterApiKey: String,
    openRouterFallbackApiKey: String,
    miniMaxApiKey: String,
    onAgentHandledChange: (Boolean) -> Unit,
    onConfirmBeforeExecuteChange: (Boolean) -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onSaveGeminiApiKey: () -> Unit,
    onOpenRouterApiKeyChange: (String) -> Unit,
    onSaveOpenRouterApiKey: () -> Unit,
    onOpenRouterFallbackApiKeyChange: (String) -> Unit,
    onSaveOpenRouterFallbackApiKey: () -> Unit,
    onMiniMaxApiKeyChange: (String) -> Unit,
    onSaveMiniMaxApiKey: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات") },
        text = {
            // The controls plus supporting text overflow a short screen; without this the
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

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("تأیید قبل از اجرا")
                    Switch(checked = confirmBeforeExecute, onCheckedChange = onConfirmBeforeExecuteChange)
                }
                Text(
                    text = "با خاموش‌کردن آن، فقط دستورهای حذف تأیید می‌گیرند — آن‌ها هیچ‌وقت بدون تأیید اجرا نمی‌شوند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "مدل‌ها",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "مدل هر نقش (اپ MIA، TEC، PO، QC، مدیر بریف) از دکمهٔ «مدل‌های " +
                        "پیش‌فرض» در نوار بالا برای پروژه‌های جدید، و از آیکن مدل روی کارت هر " +
                        "پروژه برای همان پروژه تنظیم می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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
                    label = { Text("کلید API اوپن‌روتر (مدل‌های رایگان)") },
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

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = miniMaxApiKey,
                    onValueChange = onMiniMaxApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("کلید API مینی‌مکس (MiniMax M3 پولی)") },
                    supportingText = {
                        Text(
                            "از platform.minimax.io بگیرید. برای گزینه‌های MiniMax لازم است و " +
                                "به‌عنوان سکرت MINIMAX_API_KEY روی مخزن‌ها هم ذخیره می‌شود."
                        )
                    },
                    visualTransformation = PasswordVisualTransformation()
                )
                TextButton(
                    onClick = onSaveMiniMaxApiKey,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("ذخیره کلید") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}
