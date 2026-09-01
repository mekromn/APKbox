package com.mekromn.apkbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KBlack = Color(0xFF000000)
private val KDeep = Color(0xFF0D0D0D)
private val KRaised = Color(0xFF292929)
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
    tertiaryContainer = KRaised,
    onTertiaryContainer = KWhite,
    background = KBlack,
    onBackground = KWhite,
    surface = KBlack,
    onSurface = KWhite,
    surfaceVariant = KKey,
    onSurfaceVariant = KSecondary,
    surfaceDim = KBlack,
    surfaceBright = KKey,
    surfaceContainerLowest = KBlack,
    surfaceContainerLow = KDeep,
    surfaceContainer = KRaised,
    surfaceContainerHigh = KKey,
    surfaceContainerHighest = KKey,
    outline = KMid,
    outlineVariant = KDeep,
    scrim = KBlack,
    error = KBlue,
    onError = KWhite,
    errorContainer = KDeep,
    onErrorContainer = KWhite,
)

@Composable
fun APKboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KeyboardDarkColors, content = content)
}
