package com.vibenav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = NeonAmber,
    onSecondary = Color(0xFF452200),
    secondaryContainer = Color(0xFF633300),
    onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = EcoGreen,
    background = CockpitBackground,
    onBackground = TextPrimary,
    surface = CockpitSurface,
    onSurface = TextPrimary,
    surfaceVariant = CockpitSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = CockpitBorder,
    error = WarningRed
)

@Composable
fun VibeNavTheme(
    darkTheme: Boolean = true, // Default to high-contrast cockpit dark theme for motorcycle safety
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
