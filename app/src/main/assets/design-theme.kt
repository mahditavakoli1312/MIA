// Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/).
//
// The theme: colours, type and writing direction, wrapped once around the whole app.
//
// The visual language is MIA's own — a near-black canvas with neon accents — so a project MIA
// generated looks like MIA made it. Dark-first is a decision, not a default: the accents only work
// against a fixed dark ground, which is also why Android 12 dynamic colour is deliberately not
// used. A wallpaper must not be able to repaint this app.
package mia.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

// Neon accents — the record button, active states, anything that should catch the eye.
val NeonGreen = Color(0xFF39FF14)
val NeonCyan = Color(0xFF00F0FF)
val NeonGreenDim = Color(0xFF1F8F0B)
val NeonCyanDim = Color(0xFF0090A0)

// Near-black surfaces: a true dark theme, not a dimmed light one.
val BackgroundBlack = Color(0xFF0A0B0D)
val SurfaceDark = Color(0xFF14161A)
val SurfaceElevatedDark = Color(0xFF1C1F24)
val OutlineDark = Color(0xFF2A2E35)

val OnSurfaceLight = Color(0xFFE6E8EB)
val OnSurfaceMuted = Color(0xFF9AA1AC)
val ErrorRed = Color(0xFFFF5252)

private val DarkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = BackgroundBlack,
    secondary = NeonCyan,
    onSecondary = BackgroundBlack,
    tertiary = NeonCyanDim,
    onTertiary = OnSurfaceLight,
    background = BackgroundBlack,
    onBackground = OnSurfaceLight,
    surface = SurfaceDark,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceElevatedDark,
    onSurfaceVariant = OnSurfaceMuted,
    outline = OutlineDark,
    error = ErrorRed,
    onError = BackgroundBlack
)

// Present so a device in light mode is still legible, not because this app is meant to be light.
private val LightColors = lightColorScheme(primary = NeonGreenDim, secondary = NeonCyanDim)

/**
 * The app's type family.
 *
 * `FontFamily.Default` on purpose: a freshly generated project has no font files, and referencing
 * `R.font.vazirmatn_regular` before they exist fails the build. To switch the whole app to
 * Vazirmatn (which is what MIA's own UI uses, and what Persian text deserves):
 *
 *   1. Download https://github.com/rastikerdar/vazirmatn/releases and put the .ttf files in
 *      `res/font/` as vazirmatn_regular / _medium / _semibold / _bold.
 *   2. Replace this one line with the commented FontFamily below.
 *
 * Nothing else in the app names a font, so that is the entire change.
 */
val AppFontFamily: FontFamily = FontFamily.Default
// val AppFontFamily = FontFamily(
//     Font(R.font.vazirmatn_regular, FontWeight.Normal),
//     Font(R.font.vazirmatn_medium, FontWeight.Medium),
//     Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
//     Font(R.font.vazirmatn_bold, FontWeight.Bold)
// )

/**
 * The type scale. Persian needs more line height than Latin at the same size — the descenders and
 * the diacritics collide otherwise — so every style here is roomier than Material's default.
 */
val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 24.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

/**
 * Wrap the whole app in this, once, in `MainActivity.setContent { MiaTheme { … } }`.
 *
 * RTL is applied HERE and nowhere else. A screen that re-applies it is either redundant or is
 * fighting the root, and both end in a layout that mirrors twice and comes out LTR again. Give a
 * *specific block* the other direction when it needs one (code, a table, English), never a screen.
 */
@Composable
fun MiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Set false only for a project whose content language is left-to-right. */
    rightToLeft: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr,
            content = content
        )
    }
}
