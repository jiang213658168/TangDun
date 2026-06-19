package com.tangdun.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 糖盾 TangDun 颜色系统 (v2.0 产品级重构)
 *
 * 设计理念:
 *  - 现代健康 App 风格 (参照 Glucose Buddy / Dexcom Clarity / MyFitnessPal)
 *  - 温暖的湖蓝绿为主色, 告别传统医院冷峻感
 *  - 完整支持 Light/Dark 双主题
 *  - 语义色按血糖状态分级 (正常绿 / 高黄 / 低橙)
 *
 * 色板结构:
 *  - 主品牌色: Teal 家族 (#0EA5A5) - 信任、专业、健康
 *  - 强调色: Cyan 家族 (#06B6D4) - 活力、清新
 *  - 血糖语义: 正常翠绿 / 警戒琥珀 / 危险红
 *  - 中性色: Slate 家族 - 现代、低饱和
 */

// ════════════════════════════════════════
// 主品牌色 (Teal 家族 - 现代健康蓝绿)
// ════════════════════════════════════════
val Teal50  = Color(0xFFF0FDFA)
val Teal100 = Color(0xFFCCFBF1)
val Teal200 = Color(0xFFCCFBF1)  // (备用别名, 同 Teal100)
val Teal300 = Color(0xFF5EEAD4)
val Teal400 = Color(0xFF2DD4BF)
val Teal500 = Color(0xFF14B8A6)
val Teal600 = Color(0xFF0D9488)
val Teal700 = Color(0xFF0F766E)
val Teal800 = Color(0xFF115E59)
val Teal900 = Color(0xFF134E4A)

// 兼容旧代码 (保留别名)
// 注意: 旧 Primary=#0D7377 现在升级为 #0D9488 (Teal600), 视觉上更亮、更现代
val Primary       = Teal600
val PrimaryLight  = Teal400
val PrimaryDark   = Teal800
val PrimaryBg     = Teal100

// ════════════════════════════════════════
// 辅助色 (Cyan 家族 - 数据可视化用)
// ════════════════════════════════════════
val Cyan400 = Color(0xFF22D3EE)
val Cyan500 = Color(0xFF06B6D4)
val Cyan600 = Color(0xFF0891B2)
val Cyan700 = Color(0xFF0E7490)

// 兼容旧代码
val Secondary     = Cyan700
val SecondaryLight = Cyan500

// ════════════════════════════════════════
// 强调色 (强调重要操作/事件)
// ════════════════════════════════════════
val Coral500 = Color(0xFFEF4444)  // 柔和红, 不刺眼
val Coral400 = Color(0xFFF87171)
val Amber500 = Color(0xFFF59E0B)  // 琥珀橙, 高血糖提醒
val Amber400 = Color(0xFFFBBF24)

val Accent       = Coral500
val AccentWarm   = Amber500

// ════════════════════════════════════════
// 血糖语义色 (按 TIR 国际共识分级)
// ════════════════════════════════════════
// 严重低 (<3.0) - 危险红
val GlucoseSevereLow  = Color(0xFFDC2626)
// 低 (3.0-3.9) - 警示橙
val GlucoseLow        = Color(0xFFEA580C)
// 正常偏低 (3.9-4.5) - 柔和橙
val GlucoseLowNormal  = Color(0xFFFB923C)
// 正常 (4.5-7.8) - 健康绿
val GlucoseNormal     = Color(0xFF10B981)
// 正常偏高 (7.8-10.0) - 柔和黄
val GlucoseHighNormal = Color(0xFFFACC15)
// 高 (10.0-13.9) - 琥珀
val GlucoseHigh       = Color(0xFFF59E0B)
// 严重高 (>13.9) - 危险红橙
val GlucoseSevereHigh = Color(0xFFDC2626)

/**
 * 根据血糖值返回对应的语义色
 * @param value 血糖值 (mmol/L)
 * @param low 用户目标下限 (默认 3.9)
 * @param high 用户目标上限 (默认 10.0)
 */
fun glucoseColor(value: Double, low: Double = 3.9, high: Double = 10.0): Color {
    return when {
        value < 3.0 -> GlucoseSevereLow
        value < low -> GlucoseLow
        value < low + 0.6 -> GlucoseLowNormal
        value <= high -> GlucoseNormal
        value < high + 3.9 -> GlucoseHighNormal
        value < 13.9 -> GlucoseHigh
        else -> GlucoseSevereHigh
    }
}

// ════════════════════════════════════════
// 中性色 (Slate 家族 - 现代灰)
// ════════════════════════════════════════
val Slate50  = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)

// 兼容旧代码
val TextDark       = Slate900
val TextBody       = Slate600
val TextHint       = Slate400
val TextWhite      = Color(0xFFFFFFFF)
val BgLight        = Slate50
val BgWhite        = Color(0xFFFFFFFF)
val BgCard         = Color(0xFFFFFFFF)  // 卡片改用纯白, 配合阴影层次
val Divider        = Slate200
val SurfaceVariant = Slate100

// ════════════════════════════════════════
// 状态色 (语义化)
// ════════════════════════════════════════
val Success = Color(0xFF10B981)
val Warning = Color(0xFFF59E0B)
val Danger  = Color(0xFFEF4444)
val Info    = Color(0xFF06B6D4)

// 兼容旧代码别名
val TextPrimary   = TextDark
val TextSecondary = TextBody
val AlertSuccess  = Success
val AlertWarning  = Warning
val AlertCritical = Danger
val AlertInfo     = Info

// ════════════════════════════════════════
// 图表色 (3 色 + 渐变填充)
// ════════════════════════════════════════
val Chart1 = Teal500
val Chart2 = Cyan500
val Chart3 = Amber500
val ChartGrid = Slate200
val ChartTarget = Color(0x3310B981)  // 目标区间半透明
val ChartFill = Color(0x1A14B8A6)    // 曲线下渐变填充

// 兼容旧代码
val ChartLine1 = Chart1
val ChartLine2 = Chart2
val ChartLine3 = Chart3
val GiLow = Success
val GiMedium = Warning
val GiHigh = Danger

// ════════════════════════════════════════
// Dark Theme 专用 (在 Theme.kt 中使用)
// ════════════════════════════════════════
val DarkBackground   = Color(0xFF0F172A)  // 深石板蓝
val DarkSurface      = Color(0xFF1E293B)  // 深灰
val DarkSurfaceHigh  = Color(0xFF334155)  // 卡片深色
val DarkOnSurface    = Color(0xFFF1F5F9)
val DarkOnSurfaceVar = Color(0xFFCBD5E1)
val DarkDivider      = Color(0xFF334155)
