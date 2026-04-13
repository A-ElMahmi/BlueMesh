package com.example.blessed3.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorPalette = darkColorScheme(
    primary = AppPrimary,
    secondary = AppSecondary,
    onPrimary = Color.White,
    onSecondary = Color(0xFF1A1A1A),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE0E0E0)
)

private val LightColorPalette = lightColorScheme(
    primary = AppPrimary,
    secondary = AppSecondary,
    onPrimary = Color.White,
    onSecondary = Color(0xFF1A1A1A),
    background = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A)
)

@Composable
fun Blessed3Theme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColorPalette else LightColorPalette

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
