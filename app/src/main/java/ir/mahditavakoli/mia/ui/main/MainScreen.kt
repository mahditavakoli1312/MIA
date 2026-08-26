package ir.mahditavakoli.mia.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit = {},
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val amplitude by viewModel.micAmplitude.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }

    var hasRecordPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) viewModel.onMicClick()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // The whole screen is Persian-first, so flip layout direction once at the root
    // rather than mirroring every individual row.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            // The command field is in the bottom bar, so the whole scaffold has to lift with
            // the keyboard — the activity draws edge-to-edge and would otherwise cover it.
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text("پروژه‌های من") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "تنظیمات"
                            )
                        }
                        IconButton(onClick = onLogout) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "خروج"
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
            // Typed commands live in the bottom bar; the mic FAB docks above it, so the two
            // ways of giving MIA a command sit together instead of competing for the corner.
            bottomBar = {
                CommandInputBar(
                    text = uiState.commandText,
                    refinedPrompt = uiState.refinedPrompt,
                    stage = uiState.stage,
                    canSend = uiState.canSendText,
                    onTextChange = viewModel::onCommandTextChange,
                    onSend = viewModel::onSendText,
                    onDismissRefinedPrompt = viewModel::clearRefinedPrompt
                )
            },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                MicFab(
                    recordingState = uiState.recordingState,
                    amplitude = amplitude,
                    onClick = {
                        if (hasRecordPermission) {
                            viewModel.onMicClick()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    uiState.isLoadingProjects && uiState.projects.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )

                    uiState.projects.isEmpty() -> Text(
                        text = "هنوز پروژه‌ای نساخته‌اید.\nدکمه میکروفون را بزنید یا بنویسید:\n«یک پروژه جدید به اسم … بساز»",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Extra bottom room so the docked mic FAB never covers the last card.
                        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 72.dp)
                    ) {
                        items(uiState.projects, key = { it.id ?: it.name }) { project ->
                            ProjectCard(project, modifier = Modifier.padding(bottom = 12.dp))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.recordingState != RecordingState.Idle,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    StatusBanner(uiState.recordingState, uiState.stage)
                }
            }
        }

        if (showSettings) {
            SettingsDialog(
                agentHandledByDefault = uiState.agentHandledByDefault,
                geminiApiKey = uiState.geminiApiKey,
                openRouterApiKey = uiState.openRouterApiKey,
                openRouterFallbackApiKey = uiState.openRouterFallbackApiKey,
                onAgentHandledChange = viewModel::onAgentHandledChange,
                onGeminiApiKeyChange = viewModel::onGeminiApiKeyChange,
                onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                onOpenRouterApiKeyChange = viewModel::onOpenRouterApiKeyChange,
                onSaveOpenRouterApiKey = viewModel::saveOpenRouterApiKey,
                onOpenRouterFallbackApiKeyChange = viewModel::onOpenRouterFallbackApiKeyChange,
                onSaveOpenRouterFallbackApiKey = viewModel::saveOpenRouterFallbackApiKey,
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun StatusBanner(state: RecordingState, stage: CommandStage) {
    val text = when (state) {
        RecordingState.Listening -> "در حال شنیدن..."
        // Naming the stage matters most for typed commands, where "processing" covers two
        // separate model calls and the wait is long enough to look like a hang.
        RecordingState.Processing -> when (stage) {
            CommandStage.REFINING -> "در حال آماده‌سازی پرامپت..."
            CommandStage.UNDERSTANDING -> "در حال درک دستور..."
            CommandStage.EXECUTING -> "در حال اجرا..."
            CommandStage.NONE -> "در حال پردازش..."
        }
        RecordingState.Idle -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
