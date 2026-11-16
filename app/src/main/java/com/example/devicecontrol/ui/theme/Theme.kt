package com.example.devicecontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF3A3A3A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = Color(0xFF141414),
    tertiary = Color(0xFF525252),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E5E5),
    onTertiaryContainer = Color(0xFF141414),
    background = Color(0xFFF6F6F6),
    onBackground = Color(0xFF141414),
    surface = Color.White,
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF666666),
    surfaceTint = Color.Transparent,
    outline = Color(0xFFD6D6D6),
    outlineVariant = Color(0xFFE8E8E8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEBEBEB),
)

@Composable
fun DeviceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
