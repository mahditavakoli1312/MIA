package ir.mahditavakoli.mia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// MIA is a single-brand, dark-first app — neon accents need a fixed black canvas,
// so we deliberately skip Android 12 dynamic color instead of letting wallpaper override it.
private val MIADarkColorScheme = darkColorScheme(
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

private val MIALightColorScheme = lightColorScheme(
    primary = NeonGreenDim,
    secondary = NeonCyanDim
)

@Composable
fun MIATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) MIADarkColorScheme else MIALightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
