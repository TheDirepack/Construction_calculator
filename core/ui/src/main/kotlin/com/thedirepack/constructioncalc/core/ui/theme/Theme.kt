package com.thedirepack.constructioncalc.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF7B2CBF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0DBFF),
    onPrimaryContainer = Color(0xFF2C0051),
    secondary = Color(0xFF665A6F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDDDF6),
    onSecondaryContainer = Color(0xFF211829),
    tertiary = Color(0xFF8C5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC0),
    onTertiaryContainer = Color(0xFF2D1600),
    background = Color(0xFFFFF7FF),
    onBackground = Color(0xFF1E1A20),
    surface = Color(0xFFFFF7FF),
    onSurface = Color(0xFF1E1A20),
    surfaceVariant = Color(0xFFEADFEA),
    onSurfaceVariant = Color(0xFF4C444F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF1FA),
    surfaceContainer = Color(0xFFF4EBF4),
    surfaceContainerHigh = Color(0xFFEEE6EE),
    surfaceContainerHighest = Color(0xFFE8E0E8),
    outline = Color(0xFF7D747F),
    outlineVariant = Color(0xFFCEC3CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFDDB8FF),
    onPrimary = Color(0xFF480081),
    primaryContainer = Color(0xFF6200A8),
    onPrimaryContainer = Color(0xFFF0DBFF),
    secondary = Color(0xFFD0C1DA),
    onSecondary = Color(0xFF362D3F),
    secondaryContainer = Color(0xFF4D4357),
    onSecondaryContainer = Color(0xFFEDDDF6),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6B3B00),
    onTertiaryContainer = Color(0xFFFFDCC0),
    background = Color(0xFF151217),
    onBackground = Color(0xFFE8E0E8),
    surface = Color(0xFF151217),
    onSurface = Color(0xFFE8E0E8),
    surfaceVariant = Color(0xFF4C444F),
    onSurfaceVariant = Color(0xFFCEC3CF),
    surfaceContainerLowest = Color(0xFF100D12),
    surfaceContainerLow = Color(0xFF1E1A20),
    surfaceContainer = Color(0xFF221E24),
    surfaceContainerHigh = Color(0xFF2D282E),
    surfaceContainerHighest = Color(0xFF383339),
    outline = Color(0xFF978E99),
    outlineVariant = Color(0xFF4C444F),
)

@Composable
fun ConstructionCalcTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
