package dev.addblocker.adshield.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CE1A6),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFF7EF8C3),
    secondary = Color(0xFF9FD3BB),
    onSecondary = Color(0xFF0A3529),
    background = Color(0xFF0B1210),
    onBackground = Color(0xFFE1E9E4),
    surface = Color(0xFF111916),
    onSurface = Color(0xFFE1E9E4),
    surfaceVariant = Color(0xFF1C2823),
    onSurfaceVariant = Color(0xFFB8C7BF),
    outline = Color(0xFF41544A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F7A5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2D1),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF8),
    onBackground = Color(0xFF10201A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10201A),
    surfaceVariant = Color(0xFFDFEDE6),
    onSurfaceVariant = Color(0xFF43554C),
    outline = Color(0xFF73857B),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun AdShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
