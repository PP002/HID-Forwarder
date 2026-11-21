package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pure OLED Black ColorScheme with high contrast
private val OledBlackColorScheme = darkColorScheme(
    primary = Cyan400,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0F172A),
    onPrimaryContainer = Cyan300,
    secondary = Emerald400,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Emerald400,
    tertiary = Amber500,
    background = OledBlack,              // True 0% brightness OLED Black (#000000)
    onBackground = Slate100,
    surface = OledSurface,               // Deep contrast OLED surface (#09090B)
    onSurface = Slate100,
    surfaceVariant = OledSurfaceVariant, // Elevated card/container (#141417)
    onSurfaceVariant = Slate400,
    outline = OledBorder,
    outlineVariant = Color(0xFF18181B)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to true OLED Black mode
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OledBlackColorScheme,
        typography = Typography,
        content = content
    )
}
