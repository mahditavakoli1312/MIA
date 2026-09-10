// Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/).
//
// The parts every screen is assembled from.
//
// This file exists because of one observation about small models: asked to *design* a screen they
// produce something different every time, and asked to *assemble* pre-made parts they produce
// something consistent. So the parts are made here, once, by someone who could see the whole app.
//
// A screen uses these and the tokens in Tokens.kt. It does not reach for a bare Material
// component, a raw Color(0x…) or a bare .dp — CI checks for exactly that.
package mia.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Every screen's outer frame: a top bar with an optional back arrow, a snackbar host, and the
 * app's background colour.
 *
 * Using it means back arrows are mirrored correctly in RTL, titles truncate the same way
 * everywhere, and no screen forgets its snackbar host and then silently drops its error messages.
 *
 * @param onBack null on a root screen; non-null draws the (auto-mirrored) back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiaScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(
                                // Auto-mirrored: in an RTL layout this has to point the other way.
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = { floatingActionButton() },
        content = content
    )
}

/** The one container for a group of related things. One border, one radius, one padding. */
@Composable
fun MiaCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(Sizes.borderThickness, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(Spacing.lg)) { content() }
    }
}

/** Which of the three button roles this is. There is no fourth. */
enum class MiaButtonKind {
    /** The one action this screen exists for. At most one per screen. */
    PRIMARY,

    /** An alternative that is not the point of the screen. */
    SECONDARY,

    /** Deletes or discards something. Always confirmed before it runs. */
    DANGER
}

/**
 * A button.
 *
 * [loading] replaces the label with a spinner and disables the button, because the alternative —
 * a button that stays tappable during a two-second request — is how duplicate records get created.
 */
@Composable
fun MiaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: MiaButtonKind = MiaButtonKind.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val shape = RoundedCornerShape(Radius.sm)
    val body: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Sizes.iconSm),
                strokeWidth = Sizes.borderThickness * 2,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (kind) {
        MiaButtonKind.PRIMARY -> Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = Sizes.touchTarget),
            enabled = enabled && !loading,
            shape = shape
        ) { body() }

        MiaButtonKind.SECONDARY -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = Sizes.touchTarget),
            enabled = enabled && !loading,
            shape = shape
        ) { body() }

        MiaButtonKind.DANGER -> Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = Sizes.touchTarget),
            enabled = enabled && !loading,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) { body() }
    }
}

/**
 * A text field.
 *
 * [errorText] is a parameter rather than something a screen draws underneath, so that a field can
 * never be red without saying why — the single most common way a form becomes unusable.
 */
@Composable
fun MiaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = (errorText ?: helperText)?.let { { Text(it) } },
        isError = errorText != null,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(Radius.sm)
    )
}

/** The "nothing here yet" state. A screen without one looks broken when it is merely empty. */
@Composable
fun MiaEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xs))
            MiaButton(text = actionText, onClick = onAction, kind = MiaButtonKind.SECONDARY)
        }
    }
}

/**
 * The error state, with a retry.
 *
 * The retry is not optional. An error a user can only stare at is a dead end, and "pull to refresh"
 * is not discoverable enough to be the only way out.
 */
@Composable
fun MiaError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        MiaButton(text = "تلاش دوباره", onClick = onRetry, kind = MiaButtonKind.SECONDARY)
    }
}

/**
 * A placeholder in the shape of the content that is loading.
 *
 * Preferred over a centred spinner for a list: the layout does not jump when the data arrives, and
 * the user can already see how much is coming.
 */
@Composable
fun MiaSkeleton(
    modifier: Modifier = Modifier,
    lines: Int = 3
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        repeat(lines) { index ->
            Box(
                Modifier
                    // The last line is short, the way a real paragraph ends.
                    .fillMaxWidth(if (index == lines - 1) 0.6f else 1f)
                    .height(Spacing.lg)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface
                            )
                        ),
                        shape = RoundedCornerShape(Radius.sm)
                    )
            )
        }
    }
}

/** A full-screen centred spinner, for the rare case where a skeleton has no shape to imitate. */
@Composable
fun MiaLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** A small label — a status, a count, a tag. Reads as a pill at any text size. */
@Composable
fun MiaChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.pill)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A row of things, spaced by one token so two screens never disagree about the gap. */
@Composable
fun MiaRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
