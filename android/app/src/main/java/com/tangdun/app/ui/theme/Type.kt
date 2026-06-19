package com.tangdun.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 糖盾 TangDun 字体系统 (v2.0 产品级重构)
 *
 * 设计理念:
 *  - 使用系统默认字体 (中英文自适应, 中文走系统中文, 英文走 Roboto)
 *  - 严格遵循 M3 Typography 13 级梯度
 *  - display 系列用于血糖等关键数据展示 (大数字)
 *  - 数值字体使用 tabular 风格 (等宽数字, 数字不抖动)
 *
 * 使用建议:
 *  - 大血糖数字 → displayLarge/displayMedium (40-57sp, Bold)
 *  - 页面标题 → headlineMedium/Small (24-28sp, SemiBold)
 *  - 卡片标题 → titleLarge/Medium (16-22sp)
 *  - 正文 → bodyLarge/Medium (14-16sp)
 *  - 数字徽章 → labelSmall, 开启 tabular
 */

// ─────── 默认字体族 ───────
// 使用系统默认 (中文: PingFang/Microsoft YaHei, 英文: Roboto/SF)
// 不依赖外部字体资源, APK 体积零增长
private val DefaultFontFamily = FontFamily.Default

// 数字专用: tabular 让数字等宽, 避免对齐跳动
// M3 没直接支持, 但可以通过 fontFeatureSettings 启用
private val tabularFeature = TextStyle(fontFeatureSettings = "tnum")

val TangDunTypography = Typography(
    // ─── Display: 关键数据 (如血糖大数字) ───
    displayLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize   = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize   = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize   = 36.sp,
        lineHeight = 44.sp,
    ),

    // ─── Headline: 页面标题 ───
    headlineLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize   = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 24.sp,
        lineHeight = 32.sp,
    ),

    // ─── Title: 卡片/区块标题 ───
    titleLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    // ─── Body: 正文 ───
    bodyLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    // ─── Label: 按钮/标签 ───
    labelLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * 用于血糖等数值展示的特殊样式
 * 数字使用 tabular 字体特性 (等宽), 切换数值时不抖动
 */
val TangDunNumberStyle = TextStyle(
    fontFamily = DefaultFontFamily,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",  // 等宽数字
    fontSize = 48.sp,
    lineHeight = 56.sp,
    letterSpacing = (-0.5).sp,
)

val TangDunNumberLargeStyle = TextStyle(
    fontFamily = DefaultFontFamily,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
    fontSize = 72.sp,
    lineHeight = 80.sp,
    letterSpacing = (-1).sp,
)
