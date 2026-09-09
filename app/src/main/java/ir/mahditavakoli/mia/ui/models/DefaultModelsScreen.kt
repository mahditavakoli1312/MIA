package ir.mahditavakoli.mia.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.network.openrouter.AgentRole

/**
 * The model each role starts on in **new** projects, plus the app's own command model.
 *
 * It exists because the per-project screen cannot be the only answer: a user who has decided
 * that QC belongs on a bigger model has decided it about their way of working, not about one
 * repo, and setting it again on every project they create is the kind of chore that ends with it
 * not being set. The values here are written into the workflow files as they are uploaded, so a
 * new project is on the right models from its first `@tec` rather than after a second pass of
 * commits.
 *
 * Nothing here touches a project that already exists. That is stated on the screen rather than
 * implied, because "defaults" is exactly the word a user would expect to mean "everywhere".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultModelsScreen(
    onBack: () -> Unit,
    viewModel: DefaultModelsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("مدل‌های پیش‌فرض") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::onResetAll) { Text("بازنشانی") }
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "این انتخاب‌ها فقط روی پروژه‌های جدید اعمال می‌شوند: هنگام ساخت " +
                            "مخزن، همین مدل‌ها داخل فایل‌های ورک‌فلو نوشته می‌شوند. مدل " +
                            "پروژه‌های موجود را از صفحهٔ مدل‌های همان پروژه عوض کنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(AgentRole.entries, key = { it.id }) { role ->
                    RoleModelCard(
                        role = role,
                        selected = uiState.models[role],
                        expanded = uiState.expandedRole == role,
                        onToggleExpanded = { viewModel.onToggleExpanded(role) },
                        onSelect = { modelId -> viewModel.onModelSelected(role, modelId) },
                        footnote = when (role) {
                            // The one row here that is not about the future: it is live, on this
                            // device, from the next command typed.
                            AgentRole.APP -> "همین حالا اعمال می‌شود، نه فقط در پروژه‌های جدید."
                            else -> "در مخزن پروژه‌های جدید نوشته می‌شود."
                        }
                    )
                }
            }
        }
    }
}
