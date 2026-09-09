package ir.mahditavakoli.mia.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.ui.issues.ErrorState

/**
 * One project's AI team, a seat at a time: which model TEC codes on, which one QC reviews with,
 * which one the PO answers from, which one splits a brief — plus the app's own command model.
 *
 * This replaced a single "the model for this project" dialog, and the difference is the point.
 * The roles are not interchangeable work: reviewing a diff and splitting a brief are worth a
 * bigger model than writing the hundredth boilerplate screen, and every role sharing one free
 * model burns that model's daily request cap four times as fast. So each seat is chosen
 * separately, and the screen is honest that confirming writes commits to GitHub — a user who
 * thinks this is a local preference would be surprised by the history.
 *
 * Picks are staged rather than applied on tap: moving three roles is one round of commits, and
 * a mis-tap costs nothing until the button at the bottom is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectModelsScreen(
    projectName: String,
    onBack: () -> Unit,
    // Keyed per project so switching projects gets its own state rather than the previous
    // project's roles flashing up while the new ones load.
    viewModel: ProjectModelsViewModel = viewModel(key = "models-$projectName")
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(projectName) { viewModel.load(projectName) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("مدل‌های تیم AI")
                            Text(
                                text = projectName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.load(projectName) },
                            enabled = !uiState.isLoading && !uiState.isApplying
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "خواندن دوبارهٔ مدل‌ها"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = { ApplyBar(uiState, viewModel::onApply, viewModel::onDiscardChanges) }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    uiState.isLoading && uiState.saved.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )

                    uiState.errorMessage != null && uiState.saved.isEmpty() -> ErrorState(
                        message = uiState.errorMessage!!,
                        onRetry = { viewModel.load(projectName) },
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> RoleList(uiState, viewModel)
                }
            }
        }

        if (uiState.confirmUpdateFiles) {
            UpdateFilesDialog(
                onConfirm = viewModel::onConfirmUpdateFiles,
                onDismiss = viewModel::onDismissUpdateFiles
            )
        }
    }
}

/**
 * The offer to bring this repo's `.github` AI-team files up to the app's own version.
 *
 * It is on this screen rather than in Settings because this is where the consequence of stale
 * files is felt: a repo whose workflows predate role-scoped models silently runs every role on
 * one model no matter what is picked above, and the only honest place to say so is next to the
 * pickers that are not working. When that is the case the card leads with it; otherwise it stays
 * a quiet maintenance action, because a repo that is already current has nothing to fix.
 */
@Composable
private fun TeamFilesCard(
    isOutdated: Boolean,
    isUpdating: Boolean,
    enabled: Boolean,
    onUpdate: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isOutdated) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .padding(16.dp)
    ) {
        Text(
            text = if (isOutdated) "فایل‌های تیم AI این مخزن قدیمی‌اند" else "فایل‌های تیم AI",
            style = MaterialTheme.typography.titleSmall,
            color = if (isOutdated) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isOutdated) {
                "این مخزن فقط یک مدل مشترک دارد، پس انتخاب مدل جداگانه برای هر نقش روی آن " +
                    "اثر نمی‌کند. با به‌روزرسانی، ورک‌فلوها و اسکریپت‌های `.github` به نسخهٔ " +
                    "همین اپ می‌رسند — و حلقهٔ QC → PO → TEC هم فعال می‌شود."
            } else {
                "ورک‌فلوها و اسکریپت‌های `.github` را به نسخهٔ همین اپ می‌رساند. مدل فعلی هر " +
                    "نقش حفظ می‌شود."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isOutdated) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onUpdate,
            enabled = enabled && !isUpdating,
            modifier = Modifier.align(Alignment.End)
        ) {
            if (isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isUpdating) "در حال به‌روزرسانی..." else "به‌روزرسانی فایل‌های نقش‌ها")
        }
    }
}

/**
 * Confirms before overwriting, and names the one consequence a reader would not predict.
 *
 * The role prompts live inside the managed scripts, so a user who edited one loses that edit —
 * that is the sentence this dialog exists for. Everything else it says is reassurance: the
 * models survive, and the files it will not touch are named so "update" does not read as
 * "reset my project".
 */
@Composable
private fun UpdateFilesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("به‌روزرسانی فایل‌های تیم AI؟") },
        text = {
            Column {
                Text(
                    text = "همهٔ فایل‌های زیر `.github/` (ورک‌فلوها و اسکریپت‌های نقش‌ها) با " +
                        "نسخهٔ همین اپ جایگزین و روی گیت‌هاب commit می‌شوند.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "• مدل فعلی هر نقش خوانده و حفظ می‌شود.\n" +
                        "• `AGENTS.md` و فایل‌های `mia/design` دست نمی‌خورند.\n" +
                        "• اگر پرامپت نقش‌ها را داخل همین اسکریپت‌ها دستی تغییر داده‌اید، آن " +
                        "تغییرها از بین می‌روند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("به‌روزرسانی کن") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
private fun RoleList(uiState: ProjectModelsUiState, viewModel: ProjectModelsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "هر نقش می‌تواند روی مدل خودش اجرا شود. با «اعمال»، فایل‌های ورک‌فلو در " +
                    "همین مخزن گیت‌هاب commit می‌شوند و از اجرای بعدی، آن نقش با مدل انتخابی " +
                    "کار می‌کند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            TeamFilesCard(
                isOutdated = uiState.filesAreOutdated,
                isUpdating = uiState.isUpdatingFiles,
                enabled = !uiState.isApplying,
                onUpdate = viewModel::onUpdateFilesClick
            )
        }
        items(AgentRole.entries, key = { it.id }) { role ->
            RoleModelCard(
                role = role,
                selected = uiState.modelFor(role),
                expanded = uiState.expandedRole == role,
                onToggleExpanded = { viewModel.onToggleExpanded(role) },
                onSelect = { modelId -> viewModel.onModelSelected(role, modelId) },
                enabled = !uiState.isApplying,
                isLoading = uiState.isLoading && role != AgentRole.APP,
                dirty = uiState.isDirty(role),
                footnote = when (role) {
                    // The one row on this screen that is not about this project, and the one
                    // that takes effect without an apply. Both halves have to be said here.
                    AgentRole.APP ->
                        "روی همهٔ پروژه‌ها اثر دارد و همین حالا ذخیره می‌شود — روی گیت‌هاب نمی‌رود."
                    else -> null
                }
            )
        }
    }
}

/**
 * The bottom bar: what is about to be written, and the two things to do about it.
 *
 * Hidden entirely when nothing is staged, so a screen opened only to *look* at the models
 * doesn't carry a disabled button around.
 */
@Composable
private fun ApplyBar(
    uiState: ProjectModelsUiState,
    onApply: () -> Unit,
    onDiscard: () -> Unit
) {
    val pending = uiState.pendingChanges
    if (pending.isEmpty() && !uiState.isApplying) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "${pending.size} نقش تغییر کرده: " +
                    pending.keys.joinToString("، ") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Picking MiniMax spends real money and needs a key the repo may not hold yet, so
            // say both things before the commit, not in the failure message after.
            if (uiState.pendingSpendsMiniMax) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "دست‌کم یک نقش روی حساب پولی MiniMax شما اجرا می‌شود. کلید MiniMax از " +
                        "تنظیمات خوانده و به‌عنوان سکرت MINIMAX_API_KEY روی همین مخزن ذخیره می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDiscard, enabled = !uiState.isApplying) {
                    Text("انصراف از تغییرات")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApply, enabled = uiState.canApply) {
                    if (uiState.isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (uiState.isApplying) "در حال اعمال..." else "اعمال روی گیت‌هاب")
                }
            }
            uiState.resultMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
