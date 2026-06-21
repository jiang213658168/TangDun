package com.tangdun.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tangdun.app.util.SettingsManager

/**
 * ★ v3.0.11 共用格式化工具 — 把之前空壳的 getGlucoseUnit / getHeightCm 真用上
 *
 * 用法:
 *   val fmt = rememberGlucoseFormatter()
 *   Text(fmt(7.5))  // "7.5 mmol/L" 或 "135 mg/dL" (按设置切换)
 *
 *   val wFmt = rememberWeightFormatter()
 *   Text(wFmt(65.0))  // "65.0 kg"
 */

/**
 * Composable 工厂: 血糖值格式化 (mmol/L → mg/dL 按用户设置切换)
 * 响应式: 单位设置变化时自动重新格式化
 */
@Composable
fun rememberGlucoseFormatter(): (Double) -> String {
    val ctx = LocalContext.current
    val settings = remember { SettingsManager(ctx) }
    // ★ 监听设置变化: 设置页改了 unit 后, 所有页面自动刷新
    val unit by settings.glucoseUnitFlow.collectAsState(initial = settings.getGlucoseUnit())
    return remember(unit) { { mmol -> settings.formatGlucose(mmol) } }
}

/** Composable 工厂: 体重格式化 (kg, 保留 1 位小数) */
@Composable
fun rememberWeightFormatter(): (Double) -> String {
    return remember { { kg -> String.format("%.1f kg", kg) } }
}

/** Composable 工厂: 血糖单位标签 ("mmol/L" / "mg/dL") */
@Composable
fun rememberGlucoseUnitLabel(): String {
    val ctx = LocalContext.current
    val settings = remember { SettingsManager(ctx) }
    val unit by settings.glucoseUnitFlow.collectAsState(initial = settings.getGlucoseUnit())
    return remember(unit) { if (unit == "mgdl") "mg/dL" else "mmol/L" }
}

/** Composable 工厂: 单位转换 (mmol → 当前单位) 用于显示纯数字 + 单位分开场景 */
@Composable
fun rememberGlucoseConverter(): (Double) -> Double {
    val ctx = LocalContext.current
    val settings = remember { SettingsManager(ctx) }
    val unit by settings.glucoseUnitFlow.collectAsState(initial = settings.getGlucoseUnit())
    val converter: (Double) -> Double = { mmol ->
        if (unit == "mgdl") settings.mmolToMgdl(mmol) else mmol
    }
    return converter
}

/** ★ v3.0.11 BMI 公式: kg / m² */
fun calcBMI(weightKg: Double, heightCm: Int): Double {
    val h = heightCm.coerceAtLeast(50).toDouble() / 100.0
    return weightKg / (h * h)
}

/** BMI 分类 */
fun bmiCategory(bmi: Double): String = when {
    bmi < 18.5 -> "偏瘦"
    bmi < 24.0 -> "正常"
    bmi < 28.0 -> "超重"
    bmi < 32.0 -> "肥胖"
    else -> "重度肥胖"
}