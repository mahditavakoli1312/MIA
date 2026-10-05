// Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/).
//
// A worked example of a screen in this project. Copy it, rename it, replace the content.
//
// It shows the four states every screen must handle — loading, empty, error+retry, content — and
// it is written entirely from the components in MiaComponents.kt and the tokens in Tokens.kt.
// There is not one raw colour and not one bare .dp in this file, which is also what CI checks for
// in every *Screen.kt.
//
// Read the comments in order; they are the shortest description of how a screen here works.
package mia.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * The state of one screen, as ONE object.
 *
 * Four flags rather than a sealed hierarchy, because the states overlap in practice: a refresh over
 * existing content is `isLoading` *and* has items, and a list that keeps its rows visible while it
 * reloads is the difference between a calm screen and a flickering one.
 */
data class ExampleUiState(
    val isLoading: Boolean = true,
    val items: List<String> = emptyList(),
    /** Non-null when the last load failed. The screen shows a retry, never a bare message. */
    val errorMessage: String? = null,
    val draft: String = "",
    val isSubmitting: Boolean = false
) {
    /** Content is what the screen shows when it has something; everything else is a fallback. */
    val hasContent: Boolean get() = items.isNotEmpty()

    /** Empty is only empty once loading has finished and nothing went wrong. */
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && items.isEmpty()

    val canSubmit: Boolean get() = draft.isNotBlank() && !isSubmitting
}

/**
 * The screen.
 *
 * Note what it takes: state and lambdas. It never receives a ViewModel and never touches a
 * repository — that is what makes it previewable in every state, which is what the four @Preview
 * functions at the bottom of this file depend on.
 *
 * @param onRetry re-runs the failed load.
 * @param onEvent one-shot messages from the ViewModel (a Channel on the other side), shown as a
 *        snackbar. Never state: a message kept in state re-appears after every rotation.
 */
@Composable
fun ExampleScreen(
    state: ExampleUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    events: kotlinx.coroutines.flow.Flow<String>? = null
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(events) {
        events?.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    MiaScaffold(
        title = "نمونهٔ صفحه",
        subtitle = "همهٔ چهار حالت",
        onBack = onBack,
        snackbarHostState = snackbarHostState
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg)
        ) {
            // 1. The write half of the screen. Disabled while a submit is in flight, so the same
            //    thing cannot be created twice by an impatient second tap.
            MiaTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                label = "عنوان",
                placeholder = "چه چیزی اضافه می‌کنید؟",
                enabled = !state.isSubmitting
            )
            Spacer(Modifier.height(Spacing.md))
            MiaButton(
                text = "افزودن",
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSubmit,
                loading = state.isSubmitting
            )
            Spacer(Modifier.height(Spacing.lg))

            // 2. The four states, in the order they matter. An error outranks stale content: the
            //    user must not act on a list that failed to refresh without being told.
            when {
                state.errorMessage != null -> MiaError(
                    message = state.errorMessage,
                    onRetry = onRetry
                )

                // A first load shows the shape of what is coming; a refresh keeps the rows.
                state.isLoading && !state.hasContent -> MiaSkeleton(lines = 4)

                state.isEmpty -> MiaEmptyState(
                    title = "هنوز چیزی اینجا نیست",
                    description = "اولین مورد را با فیلد بالا اضافه کنید.",
                    actionText = "بارگذاری دوباره",
                    onAction = onRetry
                )

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(state.items, key = { it }) { item ->
                        MiaCard {
                            MiaRow {
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(0.7f)
                                )
                                MiaChip(text = "برچسب")
                            }
                        }
                    }
                }
            }
        }
    }
}

// The previews are part of the deliverable, not a nicety: a screen whose four states cannot be
// looked at side by side is a screen whose empty and error states nobody has ever seen.

@Preview(name = "محتوا", showBackground = true)
@Composable
private fun ExampleContentPreview() {
    MiaTheme {
        ExampleScreen(
            state = ExampleUiState(isLoading = false, items = listOf("مورد اول", "مورد دوم")),
            onBack = {},
            onDraftChange = {},
            onSubmit = {},
            onRetry = {}
        )
    }
}

@Preview(name = "در حال بارگذاری", showBackground = true)
@Composable
private fun ExampleLoadingPreview() {
    MiaTheme {
        ExampleScreen(
            state = ExampleUiState(isLoading = true),
            onBack = {},
            onDraftChange = {},
            onSubmit = {},
            onRetry = {}
        )
    }
}

@Preview(name = "خالی", showBackground = true)
@Composable
private fun ExampleEmptyPreview() {
    MiaTheme {
        ExampleScreen(
            state = ExampleUiState(isLoading = false),
            onBack = {},
            onDraftChange = {},
            onSubmit = {},
            onRetry = {}
        )
    }
}

@Preview(name = "خطا", showBackground = true)
@Composable
private fun ExampleErrorPreview() {
    MiaTheme {
        ExampleScreen(
            state = ExampleUiState(isLoading = false, errorMessage = "اتصال به سرور برقرار نشد"),
            onBack = {},
            onDraftChange = {},
            onSubmit = {},
            onRetry = {}
        )
    }
}
