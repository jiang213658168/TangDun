package com.tangdun.app.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = Primary,
    onPrimary = TextWhite,
    primaryContainer = PrimaryBg,
    onPrimaryContainer = PrimaryDark,
    secondary = Secondary,
    onSecondary = TextWhite,
    secondaryContainer = Color(0xFFE8E8F0),
    onSecondaryContainer = Secondary,
    tertiary = Accent,
    tertiaryContainer = Color(0xFFFFE5E5),
    onTertiaryContainer = Color(0xFF7F0000),
    background = BgLight,
    onBackground = TextDark,
    surface = BgWhite,
    onSurface = TextDark,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextBody,
    outline = Divider,
    error = Danger,
    onError = TextWhite,
)

@Composable
fun TangDunTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = PrimaryDark.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = LightScheme, typography = TangDunTypography, content = content)
}
