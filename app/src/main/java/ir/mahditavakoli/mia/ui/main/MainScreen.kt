package ir.mahditavakoli.mia.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.data.model.Project
import kotlinx.coroutines.delay

/**
 * @param onOpenIssues opens the GitHub issues of one project — the counts shown on its card are
 *        the entry point, so this is what the card's issues strip taps into.
 * @param onOpenSpend opens the token-spend screen. It lives in the top bar rather than on a card
 *        because the number it shows is the whole system's, not one project's.
 * @param onOpenProjectModels opens one project's model screen — which model each seat of its AI
 *        team runs on. Reached from the card, since it is about that project's repo.
 * @param onOpenDefaultModels opens the models new projects start on. In the top bar next to
 *        Settings for the mirror-image reason [onOpenSpend] is: it belongs to no one project.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit = {},
    viewModel: MainViewModel = viewModel(),
    onOpenIssues: (Project) -> Unit = {},
    onOpenSpend: () -> Unit = {},
    onOpenProjectModels: (Project) -> Unit = {},
    onOpenDefaultModels: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val amplitude by viewModel.micAmplitude.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // The timeline shows how long each step has been running, so something has to move the clock.
    // One tick a second, and only while a step is actually running: a finished card's numbers are
    // fixed, and recomposing the whole list every second to redraw them would be pure waste.
    val isTiming = uiState.timeline?.isFinished == false
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isTiming) {
        while (isTiming) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // Polling the tracked issue is tied to the screen actually being on screen: an agent run takes
    // minutes, and a backgrounded app that kept reading GitHub would spend the shared rate limit
    // on a card nobody can see. Leaving this composable (into the issues screen) stops it too.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uiState.timeline?.issue) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startTimelinePolling()
                Lifecycle.Event.ON_STOP -> viewModel.stopTimelinePolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopTimelinePolling()
        }
    }

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

    // Asked for once, when the user first hands work to the agent — see
    // MainViewModel.notificationPermissionRequests for why not at launch. A refusal is simply
    // dropped: the watcher checks the permission itself and posts nothing without it.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var hasAskedForNotifications by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        viewModel.notificationPermissionRequests.collect {
            // Below Android 13 the permission does not exist and notifications are on by default.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasAskedForNotifications) {
                return@collect
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                hasAskedForNotifications = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
                        IconButton(onClick = onOpenSpend) {
                            Icon(
                                imageVector = Icons.Filled.Analytics,
                                contentDescription = "هزینهٔ توکن"
                            )
                        }
                        IconButton(onClick = onOpenDefaultModels) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "مدل‌های پیش‌فرض"
                            )
                        }
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
                            ProjectCard(
                                project = project,
                                modifier = Modifier.padding(bottom = 12.dp),
                                canChangeAgentModel = uiState.isGitHubConfigured,
                                onChangeAgentModel = { onOpenProjectModels(project) },
                                // Null without GitHub configured: no repo, so no issues strip.
                                issueSummary = uiState.issueSummaries[project.name]
                                    ?.takeIf { uiState.isGitHubConfigured },
                                onOpenIssues = { onOpenIssues(project) },
                                onRetryIssues = { viewModel.refreshIssueSummary(project.name) }
                            )
                        }
                    }
                }

                // The command's own progress, over the list rather than in it: it belongs to the
                // command bar at the bottom, not to any one project. "Listening" is still a
                // one-line banner — there is nothing to time or link yet — and everything from
                // "understanding" onwards is the timeline.
                AnimatedVisibility(
                    visible = uiState.recordingState is RecordingState.Listening,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    StatusBanner()
                }

                uiState.timeline?.let { timeline ->
                    CommandTimelineCard(
                        timeline = timeline,
                        now = now,
                        onToggleExpanded = viewModel::onTimelineToggleExpanded,
                        onOpenArtefact = { url -> uriHandler.openUri(url) },
                        onRetry = viewModel::onTimelineRetry,
                        onDismiss = viewModel::onTimelineDismiss,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        uiState.pendingConfirmation?.let { confirmation ->
            IntentConfirmationSheet(
                state = confirmation,
                onTitleChange = viewModel::onConfirmationTitleChange,
                onDueDateChange = viewModel::onConfirmationDueDateChange,
                onAcknowledgeChange = viewModel::onConfirmationAcknowledgeChange,
                onConfirm = viewModel::onConfirmationConfirm,
                onDismiss = viewModel::onConfirmationDismiss
            )
        }

        if (showSettings) {
            SettingsDialog(
                agentHandledByDefault = uiState.agentHandledByDefault,
                confirmBeforeExecute = uiState.confirmBeforeExecute,
                geminiApiKey = uiState.geminiApiKey,
                openRouterApiKey = uiState.openRouterApiKey,
                openRouterFallbackApiKey = uiState.openRouterFallbackApiKey,
                miniMaxApiKey = uiState.miniMaxApiKey,
                onAgentHandledChange = viewModel::onAgentHandledChange,
                onConfirmBeforeExecuteChange = viewModel::onConfirmBeforeExecuteChange,
                onGeminiApiKeyChange = viewModel::onGeminiApiKeyChange,
                onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                onOpenRouterApiKeyChange = viewModel::onOpenRouterApiKeyChange,
                onSaveOpenRouterApiKey = viewModel::saveOpenRouterApiKey,
                onOpenRouterFallbackApiKeyChange = viewModel::onOpenRouterFallbackApiKeyChange,
                onSaveOpenRouterFallbackApiKey = viewModel::saveOpenRouterFallbackApiKey,
                onMiniMaxApiKeyChange = viewModel::onMiniMaxApiKeyChange,
                onSaveMiniMaxApiKey = viewModel::saveMiniMaxApiKey,
                onDismiss = { showSettings = false }
            )
        }
    }
}

/**
 * The one status the timeline cannot show: the microphone is open.
 *
 * There is no step, no elapsed time worth counting and nothing to link to while the user is still
 * speaking — the command does not exist yet. Every stage after this one is a timeline row.
 */
@Composable
private fun StatusBanner() {
    val text = "در حال شنیدن..."
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
