package com.catsmoker.app.shared.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NothingColorScheme = darkColorScheme(
    primary = AccentPrimary,        // Nothing Red
    secondary = NothingWhite,
    tertiary = AccentTertiary,      // Nothing Grey Light
    background = NothingBlack,
    surface = NothingGrey,
    surfaceVariant = NothingGreyLight,
    onPrimary = NothingWhite,
    onSecondary = NothingBlack,
    onTertiary = NothingWhite,
    onBackground = NothingWhite,
    onSurface = NothingWhite,
    onSurfaceVariant = NothingWhite.copy(alpha = 0.7f),
    outline = NothingWhite.copy(alpha = 0.12f)
)

@Composable
fun CatsmokerTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = NothingColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            
            // Ensure edge-to-edge
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
