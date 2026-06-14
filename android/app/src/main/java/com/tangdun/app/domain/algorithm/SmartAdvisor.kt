package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.entity.InsulinRecord
import kotlin.math.abs
import kotlin.math.max

/**
 * 智能建议引擎
 *
 * 根据以下数据给出建议：
 * - 当前血糖值
 * - 血糖趋势
 * - 胰岛素IOB（活性胰岛素）
 * - 历史血糖模式
 * - 最近进食情况
 * - 最近运动情况
 *
 * 输出：
 * - 运动建议
 * - 补针建议
 * - 饮食建议
 * - 预警提醒
 */
class SmartAdvisor {

    companion object {
        // 目标范围
        const val TARGET_LOW = 3.9
        const val TARGET_HIGH = 10.0
        const val SEVERE_LOW = 3.0  // 默认值，实际用参数传入
        const val SEVERE_HIGH = 13.9

        // 胰岛素敏感因子（每单位胰岛素降低血糖 mmol/L）
        // 典型值：1单位降低1-2 mmol/L
        const val INSULIN_SENSITIVITY = 1.5

        // 碳水系数（每克碳水需要多少单位胰岛素）
        // 典型值：10-15g碳水需要1单位
        const val CARB_RATIO = 12.0

        // 速效胰岛素作用时间（小时）
        const val RAPID_INSULIN_DURATION = 4.0

        // 运动降糖系数（每分钟中等运动降低血糖 mmol/L）
        const val EXERCISE_DROP_PER_MIN = 0.02
    }

    /**
     * 建议结果
     */
    data class Advice(
        val type: AdviceType,
        val title: String,
        val message: String,
        val details: List<String>,
        val priority: Priority,
        val action: String? = null
    )

    enum class AdviceType {
        INSULIN_BOLUS,      // 补针建议
        EXERCISE,           // 运动建议
        CARB_INTAKE,        // 补充碳水
        MONITOR,            // 继续监测
        WARNING             // 预警
    }

    enum class Priority {
        HIGH, MEDIUM, LOW
    }

    /**
     * 综合分析并给出建议
     *
     * @param currentGlucose 当前血糖 (mmol/L)
     * @param trend 趋势方向
     * @param recentReadings 最近血糖记录
     * @param recentInsulin 最近胰岛素记录
     * @param lastMealCarbs 最近一餐碳水 (g)
     * @param lastMealTime 最近一餐时间 (分钟前)
     * @param recentExercise 最近运动 (分钟前)
     * @return 建议列表
     */
    fun analyze(
        currentGlucose: Double,
        trend: String?,
        recentReadings: List<GlucoseRecord>,
        recentInsulin: List<InsulinRecord>,
        lastMealCarbs: Double = 0.0,
        lastMealTime: Int = 0,
        recentExercise: Int = 0,
        targetLow: Double = TARGET_LOW,
        targetHigh: Double = TARGET_HIGH,
        severeLow: Double = SEVERE_LOW,
        severeHigh: Double = SEVERE_HIGH,
        insulinSensitivity: Double = INSULIN_SENSITIVITY,
        carbRatio: Double = CARB_RATIO
    ): List<Advice> {
        val advices = mutableListOf<Advice>()

        // 1. 计算IOB（活性胰岛素）
        val iob = calculateIOB(recentInsulin)

        // 2. 计算血糖变化率
        val roc = calculateROC(recentReadings)

        // 3. 预测30分钟后血糖
        val predicted30min = predictGlucose(currentGlucose, roc, 30)

        // 4. 根据情况给出建议

        // 严重低血糖
        if (currentGlucose < severeLow) {
            advices.add(Advice(
                type = AdviceType.WARNING,
                title = "严重低血糖！",
                message = "立即补充15-20g快速碳水",
                details = listOf(
                    "葡萄糖片、果汁、糖果",
                    "15分钟后复查血糖",
                    "如意识不清请立即拨打120"
                ),
                priority = Priority.HIGH,
                action = "立即补充碳水"
            ))
            return advices  // 紧急情况，只返回这个建议
        }

        // 低血糖
        if (currentGlucose < targetLow) {
            advices.add(Advice(
                type = AdviceType.CARB_INTAKE,
                title = "血糖偏低",
                message = "补充15g快速碳水",
                details = listOf(
                    "推荐：葡萄糖片4-5片 或 果汁150ml",
                    "15分钟后复查血糖",
                    "避免吃太多导致反弹"
                ),
                priority = Priority.HIGH,
                action = "补充碳水"
            ))
            return advices
        }

        // 严重高血糖
        if (currentGlucose > severeHigh) {
            advices.add(Advice(
                type = AdviceType.WARNING,
                title = "血糖严重偏高",
                message = "建议检测酮体",
                details = listOf(
                    "如酮体阳性请立即就医",
                    "多喝水，促进排尿",
                    "不要剧烈运动"
                ),
                priority = Priority.HIGH,
                action = "检测酮体"
            ))

            // 补针建议
            if (iob < 2.0) {
                val correctionDose = calculateCorrectionDose(currentGlucose, 6.0, iob, insulinSensitivity)
                if (correctionDose > 0) {
                    advices.add(Advice(
                        type = AdviceType.INSULIN_BOLUS,
                        title = "建议补针",
                        message = "建议补充 ${String.format("%.1f", correctionDose)} 单位速效胰岛素",
                        details = listOf(
                            "当前血糖: ${String.format("%.1f", currentGlucose)} mmol/L",
                            "目标血糖: 6.0 mmol/L",
                            "活性胰岛素: ${String.format("%.1f", iob)} 单位",
                            "计算公式: (当前-目标) / 敏感因子 - IOB"
                        ),
                        priority = Priority.HIGH,
                        action = "补针 ${String.format("%.1f", correctionDose)}U"
                    ))
                }
            }
            return advices
        }

        // 高血糖
        if (currentGlucose > targetHigh) {
            // 补针建议
            if (iob < 2.0 && roc >= 0) {
                val correctionDose = calculateCorrectionDose(currentGlucose, 7.0, iob, insulinSensitivity)
                if (correctionDose > 0.5) {
                    advices.add(Advice(
                        type = AdviceType.INSULIN_BOLUS,
                        title = "建议补针",
                        message = "建议补充 ${String.format("%.1f", correctionDose)} 单位",
                        details = listOf(
                            "当前血糖: ${String.format("%.1f", currentGlucose)} mmol/L",
                            "目标血糖: 7.0 mmol/L",
                            "活性胰岛素: ${String.format("%.1f", iob)} 单位",
                            "血糖趋势: ${getTrendText(trend)}"
                        ),
                        priority = Priority.MEDIUM,
                        action = "补针 ${String.format("%.1f", correctionDose)}U"
                    ))
                }
            }

            // 运动建议（如果血糖不是太高）
            if (currentGlucose < 13.0 && iob < 3.0) {
                advices.add(Advice(
                    type = AdviceType.EXERCISE,
                    title = "建议适量运动",
                    message = "30分钟中等强度运动可降低血糖约 ${String.format("%.1f", 30 * EXERCISE_DROP_PER_MIN)} mmol/L",
                    details = listOf(
                        "推荐运动: 快走、骑车、游泳",
                        "运动时间: 30-45分钟",
                        "运动强度: 中等（能说话但不能唱歌）",
                        "注意: 避免空腹运动"
                    ),
                    priority = Priority.MEDIUM,
                    action = "运动30分钟"
                ))
            }

            // 监测建议
            advices.add(Advice(
                type = AdviceType.MONITOR,
                title = "继续监测",
                message = "建议1小时后复查血糖",
                details = listOf(
                    "当前趋势: ${getTrendText(trend)}",
                    "如果持续偏高，请咨询医生"
                ),
                priority = Priority.LOW
            ))
        }

        // 正常范围
        if (currentGlucose in targetLow..targetHigh) {
            // 临界低血糖: 距离下限<1.0且下降→高优先级
            val nearLow = currentGlucose - targetLow < 1.0

            // 如果有下降趋势
            if (trend == "falling" || trend == "falling_fast") {
                val prio = if (nearLow) Priority.HIGH else Priority.MEDIUM
                val msg = if (nearLow) "血糖偏低且在下降，请立即补充碳水" else "注意预防低血糖"
                advices.add(Advice(
                    type = AdviceType.MONITOR,
                    title = if (nearLow) "⚠ 接近低血糖" else "血糖正在下降",
                    message = msg,
                    details = listOf(
                        "当前血糖: ${String.format("%.1f", currentGlucose)} mmol/L",
                        "下限: ${String.format("%.1f", targetLow)} mmol/L",
                        "趋势: ${getTrendText(trend)}"
                    ),
                    priority = prio,
                    action = if (nearLow) "补充15g碳水" else null
                ))

                // 如果下降很快或已接近下限，建议补充碳水
                if (trend == "falling_fast" || roc < -0.05 || nearLow) {
                    advices.add(Advice(
                        type = AdviceType.CARB_INTAKE,
                        title = "建议补充碳水",
                        message = if (nearLow) "立即补充15-20g快速碳水" else "建议补充少量碳水",
                        details = listOf(
                            "推荐: 1片面包 或 1个水果",
                            "约15-20g碳水"
                        ),
                        priority = if (nearLow) Priority.HIGH else Priority.MEDIUM,
                        action = if (nearLow) "立即补充碳水" else "补充少量碳水"
                    ))
                }
            }

            // 如果有上升趋势
            if (trend == "rising" || trend == "rising_fast") {
                advices.add(Advice(
                    type = AdviceType.MONITOR,
                    title = "血糖正在上升",
                    message = "关注是否进食过多",
                    details = listOf(
                        "当前血糖: ${String.format("%.1f", currentGlucose)} mmol/L",
                        "趋势: ${getTrendText(trend)}",
                        "如刚进食，属正常现象"
                    ),
                    priority = Priority.LOW
                ))
            }

            // 如果IOB较高，提醒注意低血糖
            if (iob > 2.0) {
                advices.add(Advice(
                    type = AdviceType.WARNING,
                    title = "活性胰岛素较高",
                    message = "注意预防低血糖",
                    details = listOf(
                        "当前活性胰岛素: ${String.format("%.1f", iob)} 单位",
                        "建议随身携带糖果",
                        "2小时内避免剧烈运动"
                    ),
                    priority = Priority.MEDIUM
                ))
            }

            // 血糖平稳时的运动建议 (白天6-20点，排除睡前)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (trend == "stable" && currentGlucose in 5.0..8.0 && hour in 6..20) {
                advices.add(Advice(
                    type = AdviceType.EXERCISE,
                    title = "适合运动",
                    message = "当前血糖适合进行运动",
                    details = listOf(
                        "推荐运动: 有氧运动30-60分钟",
                        "运动前可适当补充碳水",
                        "运动中注意监测血糖"
                    ),
                    priority = Priority.LOW,
                    action = "可以运动"
                ))
            }
        }

        return advices
    }

    /**
     * 计算IOB（活性胰岛素）
     *
     * 使用指数衰减模型
     */
    private fun calculateIOB(insulinRecords: List<InsulinRecord>): Double {
        val now = System.currentTimeMillis()
        var totalIOB = 0.0

        for (record in insulinRecords) {
            val minutesAgo = (now - record.timestamp) / 60000.0
            if (minutesAgo < 0) continue

            // IOB只计算bolus胰岛素 (长效=24h基础, 不应算入活性)
            val (halfLife, maxDuration) = when (record.insulinType) {
                "rapid" -> 55.0 to (RAPID_INSULIN_DURATION * 60)
                "short" -> 90.0 to (6.0 * 60)
                "long", "mixed" -> continue  // 长效/预混: 不算IOB
                else -> continue
            }
            if (minutesAgo > maxDuration) continue

            val remainingFraction = Math.pow(0.5, minutesAgo / halfLife)
            totalIOB += record.doseUnits * remainingFraction
        }

        return totalIOB
    }

    /**
     * 计算血糖变化率 (mmol/L/min)
     */
    private fun calculateROC(readings: List<GlucoseRecord>): Double {
        if (readings.size < 2) return 0.0

        val recent = readings.takeLast(6)  // 最近30分钟
        if (recent.size < 2) return 0.0

        val first = recent.first()
        val last = recent.last()
        val timeDiff = (last.timestamp - first.timestamp) / 60000.0  // 分钟

        return if (timeDiff > 0) {
            (last.value - first.value) / timeDiff
        } else 0.0
    }

    /**
     * 预测未来血糖
     */
    private fun predictGlucose(current: Double, roc: Double, minutes: Int): Double {
        return current + roc * minutes
    }

    /**
     * 计算校正剂量
     *
     * 公式：(当前血糖 - 目标血糖) / 胰岛素敏感因子 - IOB
     */
    private fun calculateCorrectionDose(
        currentGlucose: Double,
        targetGlucose: Double,
        iob: Double,
        isf: Double
    ): Double {
        val correction = (currentGlucose - targetGlucose) / isf
        val dose = correction - iob
        return max(0.0, dose)
    }

    /**
     * 获取趋势文字
     */
    private fun getTrendText(trend: String?): String {
        return when (trend) {
            "rising_fast" -> "快速上升"
            "rising" -> "上升"
            "stable" -> "平稳"
            "falling" -> "下降"
            "falling_fast" -> "快速下降"
            else -> "未知"
        }
    }
}
