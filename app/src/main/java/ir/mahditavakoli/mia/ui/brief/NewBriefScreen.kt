package ir.mahditavakoli.mia.ui.brief

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The "نیت جدید" screen: one long-form intent, filed as a single `brief` issue that the PO agent
 * decomposes into TEC-sized issues.
 *
 * It is a screen rather than a dialog because a brief is meant to be several paragraphs, dictated
 * or typed in more than one pass — a sheet that covers half the display and dismisses on an
 * outside tap is the wrong container for text the user is still thinking about.
 *
 * @param onFiled called once the brief is on GitHub, with its issue number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBriefScreen(
    projectName: String,
    onBack: () -> Unit,
    onFiled: (Int) -> Unit,
    viewModel: NewBriefViewModel = viewModel(key = "brief-$projectName")
) {
    val uiState by viewModel.uiState.collectAsState()
    val amplitude by viewModel.micAmplitude.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The same gate the main screen's mic FAB uses: ask on the first tap, and remember the grant
    // so the second tap records instead of asking again. Which field the user tapped has to
    // survive the permission dialog, or the transcript would land in the wrong box.
    var hasRecordPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingMicField by rememberSaveable { mutableStateOf<BriefField?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        val field = pendingMicField
        pendingMicField = null
        if (granted && field != null) viewModel.onMicClick(field)
    }
    val onMicClick: (BriefField) -> Unit = { field ->
        if (hasRecordPermission) {
            viewModel.onMicClick(field)
        } else {
            pendingMicField = field
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(projectName) { viewModel.load(projectName) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(Unit) {
        viewModel.filed.collect { number -> onFiled(number) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("نیت جدید")
                            Text(
                                text = projectName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !uiState.isBusy) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
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
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Explainer()
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = viewModel::onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("عنوان نیت") },
                    placeholder = { Text("مثلاً: گزارش هزینهٔ هفتگی") },
                    singleLine = true,
                    enabled = !uiState.isSubmitting
                )
                Spacer(Modifier.height(12.dp))

                DictatedField(
                    value = uiState.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = "شرح نیت",
                    placeholder = "چه چیزی می‌خواهید و چرا؟ هرچه بیشتر بنویسید، تجزیهٔ PO دقیق‌تر است.",
                    helper = uiState.descriptionHint,
                    minLines = 6,
                    field = BriefField.DESCRIPTION,
                    dictation = uiState.dictation,
                    amplitude = amplitude,
                    enabled = !uiState.isSubmitting,
                    onMicClick = onMicClick
                )
                Spacer(Modifier.height(12.dp))

                DictatedField(
                    value = uiState.successCriteria,
                    onValueChange = viewModel::onCriteriaChange,
                    label = "معیار موفقیت (اختیاری)",
                    placeholder = "از کجا بفهمیم این نیت برآورده شده است؟",
                    helper = null,
                    minLines = 3,
                    field = BriefField.CRITERIA,
                    dictation = uiState.dictation,
                    amplitude = amplitude,
                    enabled = !uiState.isSubmitting,
                    onMicClick = onMicClick
                )

                uiState.errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = viewModel::submit,
                    enabled = uiState.canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (uiState.isSubmitting) "در حال ثبت..." else "ثبت نیت و سپردن به PO")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * What filing a brief actually does, said before the fields rather than after.
 *
 * Without it a "نیت جدید" screen looks like a second way to open an issue, and the one thing that
 * makes it different — that a robot will split it into several issues and start work on some of
 * them — would only become apparent after the user tapped the button.
 */
@Composable
private fun Explainer() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = "نیت یک خواستهٔ بزرگ است، نه یک تسک. با ثبت آن، ایجنت PO آن را می‌خواند و به " +
                "ایشوهای کوچکِ اندازهٔ ایجنت TEC می‌شکند؛ آن‌هایی که پیش‌نیازی ندارند بی‌درنگ در " +
                "صف کار قرار می‌گیرند و نقشهٔ کامل به‌صورت چک‌لیست زیر همین نیت نوشته می‌شود.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A multi-line field with its own microphone.
 *
 * The mic is per-field rather than one for the screen because the transcript has to land
 * somewhere, and asking "which field did you mean?" after the recording is worse than making the
 * choice part of the tap. Only the field being dictated shows an active mic; the other one's is
 * disabled, so two recordings can never be in flight at once.
 */
@Composable
private fun DictatedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    helper: String?,
    minLines: Int,
    field: BriefField,
    dictation: DictationState,
    amplitude: Float,
    enabled: Boolean,
    onMicClick: (BriefField) -> Unit
) {
    val isRecording = dictation is DictationState.Recording && dictation.field == field
    val isTranscribing = dictation is DictationState.Transcribing && dictation.field == field
    val busyElsewhere = dictation !is DictationState.Idle && !isRecording && !isTranscribing

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 120.dp),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            minLines = minLines,
            supportingText = helper?.let { { Text(it) } },
            isError = helper != null,
            enabled = enabled && !isTranscribing
        )
        MicButton(
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            amplitude = amplitude,
            enabled = enabled && !busyElsewhere,
            contentDescription = if (isRecording) "پایان گفتن $label" else "گفتن $label",
            onClick = { onMicClick(field) }
        )
    }
}

@Composable
private fun MicButton(
    isRecording: Boolean,
    isTranscribing: Boolean,
    amplitude: Float,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        // Aligned with the field's first line rather than its centre: a six-line box would
        // otherwise put the microphone halfway down the screen, away from where typing starts.
        Modifier.padding(top = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isTranscribing) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                colors = if (isRecording) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                }
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = contentDescription,
                    // The same amplitude the main screen's FAB pulses on, so a recording that is
                    // hearing nothing looks different from one that is.
                    modifier = Modifier.size((20 + 6 * amplitude.coerceIn(0f, 1f)).dp)
                )
            }
        }
    }
}
