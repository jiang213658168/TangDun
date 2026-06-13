package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.GlucoseRecord
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 血糖趋势计算器
 *
 * 参考xDrip+的趋势算法：
 * - 基于最近15-30分钟的血糖变化率
 * - 使用线性回归计算斜率
 * - 根据斜率判断趋势方向
 */
class TrendCalculator {

    companion object {
        // 趋势阈值 (mmol/L/min) - 参考xDrip+
        // xDrip+使用 mg/dL/min，我们转换为 mmol/L/min
        // xDrip+阈值: DoubleUp > 3, SingleUp > 1, Flat: -1~1, SingleDown < -1, DoubleDown < -3 (mg/dL/min)
        // 转换: 1 mg/dL = 0.0555 mmol/L
        const val RAPID_RISE_THRESHOLD = 0.17   // > 3 mg/dL/min = 0.17 mmol/L/min
        const val RISE_THRESHOLD = 0.056        // > 1 mg/dL/min = 0.056 mmol/L/min
        const val FALL_THRESHOLD = -0.056       // < -1 mg/dL/min = -0.056 mmol/L/min
        const val RAPID_FALL_THRESHOLD = -0.17  // < -3 mg/dL/min = -0.17 mmol/L/min
    }

    /**
     * 趋势类型
     */
    enum class Trend(val arrow: String, val label: String, val emoji: String) {
        RAPID_RISING("↑↑", "快速上升", "⬆️"),
        RISING("↑", "上升", "↗️"),
        STABLE("→", "平稳", "➡️"),
        FALLING("↓", "下降", "↘️"),
        RAPID_FALLING("↓↓", "快速下降", "⬇️"),
        UNKNOWN("?", "未知", "❓")
    }

    /**
     * 计算趋势
     *
     * @param readings 最近的血糖记录（按时间排序）
     * @return 趋势类型
     */
    fun calculateTrend(readings: List<GlucoseRecord>): Trend {
        if (readings.size < 4) return Trend.UNKNOWN

        // 使用最近15分钟的数据（约3个点）
        val recent = readings.takeLast(6)
        if (recent.size < 2) return Trend.UNKNOWN

        // 计算变化率 (mmol/L/min)
        val roc = calculateROC(recent)

        return when {
            roc >= RAPID_RISE_THRESHOLD -> Trend.RAPID_RISING
            roc >= RISE_THRESHOLD -> Trend.RISING
            roc >= FALL_THRESHOLD -> Trend.STABLE
            roc >= RAPID_FALL_THRESHOLD -> Trend.FALLING
            else -> Trend.RAPID_FALLING
        }
    }

    /**
     * 计算变化率 (Rate of Change)
     *
     * 使用线性回归计算斜率
     */
    fun calculateROC(readings: List<GlucoseRecord>): Double {
        if (readings.size < 2) return 0.0

        val n = readings.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        val startTime = readings.first().timestamp

        for (reading in readings) {
            val x = (reading.timestamp - startTime) / 60000.0  // 分钟
            val y = reading.value

            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        // 线性回归斜率
        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return 0.0

        val slope = (n * sumXY - sumX * sumY) / denominator
        return slope  // mmol/L/min
    }

    /**
     * 预测未来血糖
     *
     * @param currentGlucose 当前血糖
     * @param roc 变化率 (mmol/L/min)
     * @param minutes 分钟数
     * @return 预测血糖
     */
    fun predictFuture(currentGlucose: Double, roc: Double, minutes: Int): Double {
        return currentGlucose + roc * minutes
    }

    /**
     * 获取趋势描述
     */
    fun getTrendDescription(trend: Trend): String {
        return when (trend) {
            Trend.RAPID_RISING -> "血糖快速上升，注意是否进食过多"
            Trend.RISING -> "血糖正在上升"
            Trend.STABLE -> "血糖平稳"
            Trend.FALLING -> "血糖正在下降"
            Trend.RAPID_FALLING -> "血糖快速下降，注意预防低血糖"
            Trend.UNKNOWN -> "数据不足"
        }
    }
}
