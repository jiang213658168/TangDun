package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.GlucoseRecord

/**
 * 夜间低血糖监测
 *
 * 功能：
 * 1. 检测夜间低血糖风险
 * 2. 基于睡前血糖和趋势预测
 * 3. 提供睡前建议
 */
class NightMonitor {

    companion object {
        // 夜间时间段
        const val NIGHT_START_HOUR = 22
        const val NIGHT_END_HOUR = 6

        // 风险阈值
        const val BEDTIME_LOW_RISK = 6.0      // 睡前低于此值有风险
        const val BEDTIME_VERY_LOW_RISK = 5.0  // 睡前低于此值高风险
        const val RAPID_FALL_THRESHOLD = -0.05  // 快速下降阈值 (mmol/L/min)
    }

    /**
     * 夜间风险评估
     */
    data class NightRisk(
        val riskLevel: RiskLevel,
        val bedtimeGlucose: Double,
        val trend: String,
        val roc: Double,  // 变化率
        val advice: String,
        val details: List<String>
    )

    enum class RiskLevel {
        LOW,      // 低风险
        MEDIUM,   // 中风险
        HIGH      // 高风险
    }

    /**
     * 评估夜间低血糖风险
     *
     * @param recentReadings 最近血糖记录
     * @return 风险评估结果
     */
    fun assessRisk(recentReadings: List<GlucoseRecord>): NightRisk? {
        if (recentReadings.isEmpty()) return null

        val latestReading = recentReadings.last()
        val currentGlucose = latestReading.value

        // 计算趋势和变化率
        val trend = calculateTrend(recentReadings)
        val roc = calculateROC(recentReadings)

        // 评估风险
        val riskLevel = when {
            currentGlucose < BEDTIME_VERY_LOW_RISK -> RiskLevel.HIGH
            currentGlucose < BEDTIME_LOW_RISK && (trend == "falling" || trend == "falling_fast") -> RiskLevel.HIGH
            currentGlucose < BEDTIME_LOW_RISK -> RiskLevel.MEDIUM
            roc < RAPID_FALL_THRESHOLD -> RiskLevel.MEDIUM
            trend == "falling_fast" -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // 生成建议
        val advice = generateAdvice(riskLevel, currentGlucose, trend)
        val details = generateDetails(riskLevel, currentGlucose, trend, roc)

        return NightRisk(
            riskLevel = riskLevel,
            bedtimeGlucose = currentGlucose,
            trend = trend,
            roc = roc,
            advice = advice,
            details = details
        )
    }

    /**
     * 计算趋势
     */
    private fun calculateTrend(readings: List<GlucoseRecord>): String {
        if (readings.size < 4) return "stable"

        val recent = readings.takeLast(6)
        if (recent.size < 2) return "stable"

        val first = recent.first()
        val last = recent.last()
        val timeDiffMin = (last.timestamp - first.timestamp) / 60000.0
        if (timeDiffMin <= 0) return "stable"

        // 归一化变化率 (mmol/L/min)
        val roc = (last.value - first.value) / timeDiffMin

        return when {
            roc > 0.1 -> "rising_fast"
            roc > 0.03 -> "rising"
            roc < -0.1 -> "falling_fast"
            roc < -0.03 -> "falling"
            else -> "stable"
        }
    }

    /**
     * 计算变化率 (mmol/L/min)
     */
    private fun calculateROC(readings: List<GlucoseRecord>): Double {
        if (readings.size < 2) return 0.0

        val recent = readings.takeLast(6)
        if (recent.size < 2) return 0.0

        val first = recent.first()
        val last = recent.last()
        val timeDiff = (last.timestamp - first.timestamp) / 60000.0

        return if (timeDiff > 0) {
            (last.value - first.value) / timeDiff
        } else 0.0
    }

    /**
     * 生成建议
     */
    private fun generateAdvice(riskLevel: RiskLevel, glucose: Double, trend: String): String {
        return when (riskLevel) {
            RiskLevel.HIGH -> {
                if (glucose < 4.0) {
                    "血糖偏低，建议补充碳水后再睡"
                } else {
                    "血糖下降较快，建议少量进食后入睡"
                }
            }
            RiskLevel.MEDIUM -> {
                "建议设置夜间闹钟复查血糖"
            }
            RiskLevel.LOW -> {
                "血糖平稳，可以安心入睡"
            }
        }
    }

    /**
     * 生成详细建议
     */
    private fun generateDetails(
        riskLevel: RiskLevel,
        glucose: Double,
        trend: String,
        roc: Double
    ): List<String> {
        val details = mutableListOf<String>()

        details.add("当前血糖: ${String.format("%.1f", glucose)} mmol/L")
        details.add("趋势: ${getTrendText(trend)}")

        when (riskLevel) {
            RiskLevel.HIGH -> {
                details.add("⚠️ 夜间低血糖风险较高")
                if (glucose < 5.0) {
                    details.add("建议补充15-20g慢消化碳水")
                    details.add("推荐: 1片全麦面包 或 1杯牛奶")
                }
                details.add("建议2-3小时后复查血糖")
            }
            RiskLevel.MEDIUM -> {
                details.add("⚠️ 存在一定风险")
                details.add("建议设置凌晨3点闹钟")
                details.add("床边备好葡萄糖片")
            }
            RiskLevel.LOW -> {
                details.add("✓ 风险较低")
                if (trend == "stable") {
                    details.add("血糖平稳，适合入睡")
                }
            }
        }

        return details
    }

    private fun getTrendText(trend: String): String {
        return when (trend) {
            "rising_fast" -> "快速上升 ↑↑"
            "rising" -> "上升 ↑"
            "stable" -> "平稳 →"
            "falling" -> "下降 ↓"
            "falling_fast" -> "快速下降 ↓↓"
            else -> "未知"
        }
    }
}
