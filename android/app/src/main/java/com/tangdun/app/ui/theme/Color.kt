package com.tangdun.app.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 临床专业配色方案 =====

// 主色调 - 医疗青色系
val Primary = Color(0xFF007A8C)
val PrimaryDark = Color(0xFF005F6E)
val PrimaryLight = Color(0xFF4DA9B7)
val PrimaryContainer = Color(0xFFE0F7FA)
val OnPrimaryContainer = Color(0xFF001F24)

// 辅助色 - 深蓝系
val Secondary = Color(0xFF1A3C5E)
val SecondaryContainer = Color(0xFFD1E4FF)
val OnSecondaryContainer = Color(0xFF001C38)

// 第三色 - 紫色系
val Tertiary = Color(0xFF6B5778)
val TertiaryContainer = Color(0xFFF2DAFF)
val OnTertiaryContainer = Color(0xFF251431)

// 背景色
val Background = Color(0xFFF8FAFA)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFE7ECEC)
val OnBackground = Color(0xFF191C1C)
val OnSurface = Color(0xFF191C1C)
val OnSurfaceVariant = Color(0xFF414848)

// ===== 血糖等级配色（临床标准）=====
val GlucoseNormal = Color(0xFF2E7D32)       // 正常 3.9-10.0
val GlucoseLow = Color(0xFFD32F2F)          // 低血糖 <3.9
val GlucoseHigh = Color(0xFFE65100)         // 高血糖 >10.0
val GlucoseSevereLow = Color(0xFFB71C1C)    // 严重低血糖 <3.0
val GlucoseSevereHigh = Color(0xFFBF360C)   // 严重高血糖 >13.9

// ===== TIR配色 =====
val TirInRange = Color(0xFF4CAF50)
val TirBelow = Color(0xFFF44336)
val TirAbove = Color(0xFFFF9800)

// ===== GI等级配色 =====
val GiLow = Color(0xFF4CAF50)              // 低GI <55
val GiMedium = Color(0xFFFF9800)           // 中GI 55-70
val GiHigh = Color(0xFFF44336)             // 高GI >70

// ===== 预警等级配色 =====
val AlertCritical = Color(0xFFD32F2F)
val AlertWarning = Color(0xFFFF9800)
val AlertInfo = Color(0xFF1976D2)
val AlertSuccess = Color(0xFF2E7D32)

// ===== 图表配色 =====
val ChartLine1 = Color(0xFF007A8C)
val ChartLine2 = Color(0xFF4CAF50)
val ChartLine3 = Color(0xFFFF9800)
val ChartFill = Color(0x1A007A8C)
val ChartGrid = Color(0xFFE0E0E0)
val ChartTarget = Color(0x804CAF50)

// ===== 文字色 =====
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)
val TextHint = Color(0xFFBDBDBD)
val TextOnPrimary = Color(0xFFFFFFFF)

// ===== 分割线 =====
val Divider = Color(0xFFE0E0E0)
