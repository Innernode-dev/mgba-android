package com.retro.mgba.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = TextOnAccent,
    primaryContainer = GbaPurpleDark,
    onPrimaryContainer = TextPrimary,
    secondary = GbcCyan,
    onSecondary = Color.Black,
    secondaryContainer = GbcCyanBadge,
    onSecondaryContainer = TextPrimary,
    tertiary = GbGreen,
    onTertiary = Color.Black,
    tertiaryContainer = GbGreenDark,
    onTertiaryContainer = TextPrimary,
    background = OledBackground,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceBorderSubtle,
    outlineVariant = SurfaceBorderHighlight,
    error = DestructiveRed,
    onError = Color.White
)

@Composable
fun RetroMgbaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = OledBackground.toArgb()
            window.navigationBarColor = OledBackground.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RetroMgbaTypography,
        content = content
    )
}
