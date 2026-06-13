package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.AlertRecord
import com.tangdun.app.data.local.entity.GlucoseRecord

/**
 * 预警引擎
 *
 * 6类预警规则：
 * 1. 低血糖预警 (< 3.9 mmol/L)
 * 2. 高血糖预警 (> 10.0 mmol/L)
 * 3. 严重低血糖 (< 3.0 mmol/L)
 * 4. 严重高血糖 (> 13.9 mmol/L)
 * 5. 血糖快速上升 (ROC > 0.1 mmol/L/min)
 * 6. 血糖快速下降 (ROC < -0.1 mmol/L/min)
 *
 * 基于后端 app/services/alert_service.py 移植
 */
class AlertEngine {

    // 阈值配置
    companion object {
        const val TARGET_LOW = 3.9      // 目标下限 (mmol/L)
        const val TARGET_HIGH = 10.0    // 目标上限 (mmol/L)
        const val SEVERE_LOW = 3.0      // 严重低血糖阈值
        const val SEVERE_HIGH = 13.9    // 严重高血糖阈值
        const val RAPID_CHANGE = 0.1    // 快速变化阈值 (mmol/L/min)
    }

    /**
     * 检查所有预警规则
     *
     * @return 触发的预警列表
     */
    fun checkAll(
        currentValue: Double,
        trend: String?,
        predicted30min: Double? = null,
        recentROC: Double? = null
    ): List<AlertRecord> {
        val alerts = mutableListOf<AlertRecord>()
        val now = System.currentTimeMillis()

        // 1. 严重低血糖
        if (currentValue < SEVERE_LOW) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.SEVERE_LOW,
                severity = "critical",
                glucoseValue = currentValue,
                message = "严重低血糖！当前血糖 ${String.format("%.1f", currentValue)} mmol/L",
                action = AlertRecord.getAction(AlertRecord.SEVERE_LOW, currentValue),
                createdAt = now
            ))
        }
        // 2. 低血糖
        else if (currentValue < TARGET_LOW) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.LOW_GLUCOSE,
                severity = "warning",
                glucoseValue = currentValue,
                message = "低血糖预警，当前血糖 ${String.format("%.1f", currentValue)} mmol/L",
                action = AlertRecord.getAction(AlertRecord.LOW_GLUCOSE, currentValue),
                createdAt = now
            ))
        }

        // 3. 严重高血糖
        if (currentValue > SEVERE_HIGH) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.SEVERE_HIGH,
                severity = "critical",
                glucoseValue = currentValue,
                message = "严重高血糖！当前血糖 ${String.format("%.1f", currentValue)} mmol/L",
                action = AlertRecord.getAction(AlertRecord.SEVERE_HIGH, currentValue),
                createdAt = now
            ))
        }
        // 4. 高血糖
        else if (currentValue > TARGET_HIGH) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.HIGH_GLUCOSE,
                severity = "warning",
                glucoseValue = currentValue,
                message = "高血糖预警，当前血糖 ${String.format("%.1f", currentValue)} mmol/L",
                action = AlertRecord.getAction(AlertRecord.HIGH_GLUCOSE, currentValue),
                createdAt = now
            ))
        }

        // 5. 血糖快速上升
        if (recentROC != null && recentROC > RAPID_CHANGE) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.RAPID_RISE,
                severity = "warning",
                glucoseValue = currentValue,
                message = "血糖快速上升，变化率 ${String.format("%.2f", recentROC * 60)} mmol/L/h",
                action = AlertRecord.getAction(AlertRecord.RAPID_RISE, currentValue),
                createdAt = now
            ))
        }

        // 6. 血糖快速下降
        if (recentROC != null && recentROC < -RAPID_CHANGE) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.RAPID_FALL,
                severity = "warning",
                glucoseValue = currentValue,
                message = "血糖快速下降，变化率 ${String.format("%.2f", recentROC * 60)} mmol/L/h",
                action = AlertRecord.getAction(AlertRecord.RAPID_FALL, currentValue),
                createdAt = now
            ))
        }

        // 7. 预测低血糖
        if (predicted30min != null && predicted30min < TARGET_LOW) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.PREDICTED_LOW,
                severity = "warning",
                glucoseValue = currentValue,
                predictedValue = predicted30min,
                message = "预测30分钟后血糖 ${String.format("%.1f", predicted30min)} mmol/L，可能低血糖",
                action = AlertRecord.getAction(AlertRecord.PREDICTED_LOW, predicted30min),
                createdAt = now
            ))
        }

        // 8. 预测高血糖
        if (predicted30min != null && predicted30min > TARGET_HIGH) {
            alerts.add(AlertRecord(
                alertType = AlertRecord.PREDICTED_HIGH,
                severity = "info",
                glucoseValue = currentValue,
                predictedValue = predicted30min,
                message = "预测30分钟后血糖 ${String.format("%.1f", predicted30min)} mmol/L",
                action = AlertRecord.getAction(AlertRecord.PREDICTED_HIGH, predicted30min),
                createdAt = now
            ))
        }

        return alerts
    }

    /**
     * 计算血糖风险指数（GRI）
     *
     * GRI = 3.0 × (% time below 54 mg/dL) + 2.4 × (% time 54-70 mg/dL)
     *       + 1.6 × (% time above 180 mg/dL) + 0.8 × (% time above 250 mg/dL)
     *
     * 范围：0-100，越低越好
     */
    fun calculateGRI(records: List<GlucoseRecord>): Double {
        if (records.isEmpty()) return 0.0

        val total = records.size.toDouble()
        val severeLowCount = records.count { it.value < 3.0 }  // < 54 mg/dL
        val lowCount = records.count { it.value in 3.0..3.9 }   // 54-70 mg/dL
        val highCount = records.count { it.value in 10.0..13.9 } // 180-250 mg/dL
        val severeHighCount = records.count { it.value > 13.9 }  // > 250 mg/dL

        return 3.0 * (severeLowCount / total * 100) +
               2.4 * (lowCount / total * 100) +
               1.6 * (highCount / total * 100) +
               0.8 * (severeHighCount / total * 100)
    }

    /**
     * 计算TIR（目标范围内时间百分比）
     *
     * 目标范围：3.9-10.0 mmol/L
     * 临床目标：> 70%
     */
    fun calculateTIR(records: List<GlucoseRecord>): TIRResult {
        if (records.isEmpty()) return TIRResult()

        val total = records.size.toDouble()
        val inRange = records.count { it.value in TARGET_LOW..TARGET_HIGH }
        val belowRange = records.count { it.value < TARGET_LOW }
        val aboveRange = records.count { it.value > TARGET_HIGH }

        return TIRResult(
            inRange = inRange / total * 100,
            belowRange = belowRange / total * 100,
            aboveRange = aboveRange / total * 100
        )
    }
}

/**
 * TIR结果
 */
data class TIRResult(
    val inRange: Double = 0.0,      // 目标范围内百分比
    val belowRange: Double = 0.0,   // 低于目标百分比
    val aboveRange: Double = 0.0    // 高于目标百分比
) {
    /** 临床评价 */
    fun getEvaluation(): String = when {
        inRange >= 70 -> "优秀"
        inRange >= 50 -> "良好"
        inRange >= 30 -> "一般"
        else -> "需要改善"
    }
}
