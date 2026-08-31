package com.mekromn.apkbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3559A8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF565F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiaryContainer = Color(0xFFF8D8FF),
    onTertiaryContainer = Color(0xFF31103D),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainerHighest = Color(0xFFE6E2EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C9FF),
    onPrimary = Color(0xFF002D68),
    primaryContainer = Color(0xFF214781),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC2C9DB),
    onSecondary = Color(0xFF2B3040),
    secondaryContainer = Color(0xFF3E4558),
    onSecondaryContainer = Color(0xFFDDE4F8),
    tertiaryContainer = Color(0xFF56365E),
    onTertiaryContainer = Color(0xFFF7D8FF),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE5E2E9),
    surfaceVariant = Color(0xFF44464F),
    // Deliberately bright enough for metadata, hashes, timestamps and version text.
    onSurfaceVariant = Color(0xFFCBC9D2),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainerHighest = Color(0xFF34353B),
    outline = Color(0xFF95959E),
)

@Composable
fun APKboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
