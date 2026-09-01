package com.mekromn.apkbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Keyboard-matched palette sampled directly from the reference screenshot supplied for APKbox.
 * Core UI roles intentionally use the exact RGB values from that screenshot rather than an
 * approximate Material palette.
 */
private val KeyboardDarkColors = darkColorScheme(
    // Reference keyboard blue / enter key: RGB(94, 151, 246)
    primary = Color(0xFF5E97F6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF5E97F6),
    onPrimaryContainer = Color(0xFFFFFFFF),

    // Reference neutral/text ladder.
    secondary = Color(0xFFB7B7B7),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF4C4C4C),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB7B7B7),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2B2B2B),
    onTertiaryContainer = Color(0xFFFFFFFF),

    // Exact keyboard/background surfaces.
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFB7B7B7),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF4C4C4C),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF212121),
    surfaceContainerHigh = Color(0xFF2B2B2B),
    surfaceContainerHighest = Color(0xFF4C4C4C),

    // Reference neutral border tones visible in the keyboard/surfaces.
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFF383838),
    scrim = Color(0xFF000000),

    // Keep destructive/error states visibly semantic while the ordinary app palette stays exact.
    error = Color(0xFFF28B82),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF5C1B1B),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun APKboxTheme(content: @Composable () -> Unit) {
    // APKbox deliberately follows the supplied keyboard reference instead of system light/dark or
    // dynamic wallpaper colors, so the same exact RGB values are used every time.
    MaterialTheme(
        colorScheme = KeyboardDarkColors,
        content = content,
    )
}
