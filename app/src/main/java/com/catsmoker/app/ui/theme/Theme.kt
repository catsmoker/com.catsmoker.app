package com.catsmoker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryOrange,
    secondary = PrimaryOrange.copy(alpha = 0.8f),
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = Color.Gray
)

@Composable
fun CatsmokerTheme(
    darkTheme: Boolean = true, // Force dark mode by default
    content: @Composable () -> Unit
) {
    // Only use DarkColorScheme as per user request to remove theme mod
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
