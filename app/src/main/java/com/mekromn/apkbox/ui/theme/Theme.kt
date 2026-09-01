package com.mekromn.apkbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Exact RGB values sampled from the supplied keyboard screenshot itself.
private val KBlack = Color(0xFF000000)
private val KDeep = Color(0xFF0D0D0D)
private val KLow = Color(0xFF212121)
private val KPanel = Color(0xFF303030)
private val KKey = Color(0xFF4C4C4C)
private val KMid = Color(0xFF999999)
private val KSecondary = Color(0xFFB7B7B7)
private val KWhite = Color(0xFFFFFFFF)
private val KBlue = Color(0xFF5E97F6)

private val KeyboardDarkColors = darkColorScheme(
    primary = KBlue,
    onPrimary = KWhite,
    primaryContainer = KBlue,
    onPrimaryContainer = KWhite,
    secondary = KSecondary,
    onSecondary = KBlack,
    secondaryContainer = KKey,
    onSecondaryContainer = KWhite,
    tertiary = KMid,
    onTertiary = KBlack,
    tertiaryContainer = KPanel,
    onTertiaryContainer = KWhite,
    background = KBlack,
    onBackground = KWhite,
    surface = KBlack,
    onSurface = KWhite,
    surfaceVariant = KPanel,
    onSurfaceVariant = KSecondary,
    surfaceDim = KBlack,
    surfaceBright = KKey,
    surfaceContainerLowest = KBlack,
    surfaceContainerLow = KDeep,
    surfaceContainer = KLow,
    surfaceContainerHigh = KPanel,
    surfaceContainerHighest = KKey,
    outline = KMid,
    outlineVariant = KLow,
    scrim = KBlack,
    // Keep even exceptional UI states inside the requested keyboard palette.
    error = KBlue,
    onError = KWhite,
    errorContainer = KDeep,
    onErrorContainer = KWhite,
)

@Composable
fun APKboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KeyboardDarkColors, content = content)
}
