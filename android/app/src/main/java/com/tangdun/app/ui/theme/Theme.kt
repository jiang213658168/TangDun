package com.tangdun.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 糖盾 TangDun 主题系统 (v2.0)
 *
 * 设计理念:
 *  - 完整支持 Light/Dark 双主题 (跟随系统)
 *  - Android 12+ 使用动态取色 (Material You), 让系统主色渗透
 *  - 沉浸式状态栏, 让内容延伸到屏幕顶部
 *  - 统一的 M3 ColorScheme 语义色
 */

// ─────── Light Theme (主色) ───────
private val LightScheme = lightColorScheme(
    // 主品牌色
    primary             = Teal600,
    onPrimary           = Color.White,
    primaryContainer    = Teal100,
    onPrimaryContainer  = Teal900,

    // 辅助色
    secondary             = Cyan700,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFFCFFAFE),  // Cyan-100
    onSecondaryContainer  = Cyan700,

    // 第三色 (强调/警告)
    tertiary             = Amber500,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFFEF3C7),  // Amber-100
    onTertiaryContainer  = Color(0xFF92400E),  // Amber-800

    // 背景层级 (现代感: 纯白卡片 + 微灰背景, 制造层次)
    background    = Slate50,
    onBackground  = Slate900,
    surface       = Color.White,
    onSurface     = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,

    // 边框/分割
    outline       = Slate200,
    outlineVariant = Slate300,

    // 错误
    error             = Danger,
    onError           = Color.White,
    errorContainer    = Color(0xFFFEE2E2),
    onErrorContainer  = Color(0xFF991B1B),

    // 其他
    inverseSurface    = Slate800,
    inverseOnSurface  = Slate100,
    inversePrimary    = Teal400,
    scrim             = Color(0x99000000),
)

// ─────── Dark Theme (深色) ───────
private val DarkScheme = darkColorScheme(
    // 主品牌色 - 深色模式下用更亮的 Teal
    primary             = Teal400,
    onPrimary           = Teal900,
    primaryContainer    = Teal800,
    onPrimaryContainer  = Teal100,

    // 辅助色
    secondary             = Cyan400,
    onSecondary           = Color(0xFF083344),
    secondaryContainer    = Cyan700,
    onSecondaryContainer  = Color(0xFFCFFAFE),

    // 第三色
    tertiary             = Amber400,
    onTertiary           = Color(0xFF78350F),
    tertiaryContainer    = Color(0xFF92400E),
    onTertiaryContainer  = Color(0xFFFEF3C7),

    // 背景层级 (深色: 深石板蓝)
    background    = DarkBackground,
    onBackground  = DarkOnSurface,
    surface       = DarkSurface,
    onSurface     = DarkOnSurface,
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = DarkOnSurfaceVar,

    // 边框
    outline       = DarkDivider,
    outlineVariant = Color(0xFF475569),

    // 错误
    error             = Color(0xFFF87171),
    onError           = Color(0xFF7F1D1D),
    errorContainer    = Color(0xFF7F1D1D),
    onErrorContainer  = Color(0xFFFEE2E2),

    // 其他
    inverseSurface    = Slate100,
    inverseOnSurface  = Slate900,
    inversePrimary    = Teal600,
    scrim             = Color(0xCC000000),
)

@Composable
fun TangDunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Android 12+ 动态取色 (Material You), 默认开启
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Android 12+ 且启用动态取色: 让系统主色渗透
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    // 沉浸式状态栏 + 导航栏
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏: 透明背景 + 跟随主题切换 icon 颜色
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = TangDunTypography,
        shapes      = TangDunShapes,
        content     = content
    )
}
