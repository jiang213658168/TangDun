package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.AlertRecord
import com.tangdun.app.data.local.entity.GlucoseRecord

/**
 * 预警引擎 — 移植自 xDrip+ alert 系统
 *
 * 预警类型:
 * - 低血糖 (<3.9) / 严重低血糖 (<3.0)
 * - 高血糖 (>10.0) / 严重高血糖 (>13.9)
 * - 预测低/高血糖 (30分钟后)
 * - 快速上升/下降 (ROC >0.1 / <-0.1 mmol/L/min)
 * - 数据缺失 (>30分钟无读数)
 * - 传感器到期提醒
 *
 * 特性:
 * - 智能防抖: 同类预警5分钟内不重复
 * - 分级严重度: critical/warning/info
 * - 中文操作建议
 */
class AlertEngine {

    companion object {
        const val LOW = 3.9; const val SEVERE_LOW = 3.0
        const val HIGH = 10.0; const val SEVERE_HIGH = 13.9
        const val RAPID_RISE = 0.1  // mmol/L/min
        const val RAPID_FALL = -0.1
        const val MISSED_READING_MIN = 30  // 无数据超时(分钟)
    }

    // 防抖: 记录上次各类型预警时间
    private val lastAlertTime = mutableMapOf<String, Long>()

    fun checkAll(
        currentValue: Double, trend: String?, predicted30min: Double?,
        recentROC: Double?, lastReadingTime: Long? = null
    ): List<AlertRecord> {
        val alerts = mutableListOf<AlertRecord>()
        val now = System.currentTimeMillis()

        // 严重低血糖
        if (currentValue < SEVERE_LOW) {
            alerts.add(AlertRecord(alertType = AlertRecord.SEVERE_LOW, severity = "critical",
                glucoseValue = currentValue,
                message = "严重低血糖 ${String.format("%.1f", currentValue)} mmol/L！",
                action = "立即补充20g快速碳水(葡萄糖片4-5片/果汁200ml)，15分钟后复查。如意识不清请拨打120。"))
        }
        // 低血糖
        else if (currentValue < LOW) {
            alerts.add(AlertRecord(alertType = AlertRecord.LOW_GLUCOSE, severity = "warning",
                glucoseValue = currentValue,
                message = "低血糖 ${String.format("%.1f", currentValue)} mmol/L",
                action = "补充15g快速碳水(3-4片葡萄糖片/半杯果汁)，15分钟后复查。"))
        }

        // 严重高血糖
        if (currentValue > SEVERE_HIGH) {
            alerts.add(AlertRecord(alertType = AlertRecord.SEVERE_HIGH, severity = "critical",
                glucoseValue = currentValue,
                message = "严重高血糖 ${String.format("%.1f", currentValue)} mmol/L！",
                action = "请立即检测血/尿酮体。酮体阳性请立即就医。多喝水，不要剧烈运动。"))
        }
        // 高血糖
        else if (currentValue > HIGH) {
            alerts.add(AlertRecord(alertType = AlertRecord.HIGH_GLUCOSE, severity = "warning",
                glucoseValue = currentValue,
                message = "高血糖 ${String.format("%.1f", currentValue)} mmol/L",
                action = "多喝水，可适量快走20-30分钟。1小时后复查。如持续偏高考虑补针。"))
        }

        // 快速上升/下降
        if (recentROC != null) {
            if (recentROC > RAPID_RISE) {
                alerts.add(AlertRecord(alertType = AlertRecord.RAPID_RISE, severity = "info",
                    glucoseValue = currentValue,
                    message = "血糖快速上升 ${String.format("%.2f", recentROC)} mmol/L/min",
                    action = "检查是否进食过多碳水或胰岛素不足。关注趋势变化。"))
            }
            if (recentROC < RAPID_FALL) {
                alerts.add(AlertRecord(alertType = AlertRecord.RAPID_FALL, severity = "warning",
                    glucoseValue = currentValue,
                    message = "血糖快速下降 ${String.format("%.2f", recentROC)} mmol/L/min",
                    action = "注意预防低血糖！如有过量的活性胰岛素，请提前补充碳水。"))
            }
        }

        // 预测低/高血糖
        if (predicted30min != null) {
            if (predicted30min < LOW) {
                alerts.add(AlertRecord(alertType = AlertRecord.PREDICTED_LOW, severity = "warning",
                    glucoseValue = currentValue, predictedValue = predicted30min,
                    message = "预测30分钟后低血糖 ${String.format("%.1f", predicted30min)} mmol/L",
                    action = "建议提前补充15-20g慢消化碳水(全麦面包/牛奶)预防低血糖。"))
            }
            if (predicted30min > HIGH) {
                alerts.add(AlertRecord(alertType = AlertRecord.PREDICTED_HIGH, severity = "info",
                    glucoseValue = currentValue, predictedValue = predicted30min,
                    message = "预测30分钟后高血糖 ${String.format("%.1f", predicted30min)} mmol/L",
                    action = "关注饮食和胰岛素，可提前干预。"))
            }
        }

        // 数据缺失
        if (lastReadingTime != null && (now - lastReadingTime) > MISSED_READING_MIN * 60000) {
            val min = (now - lastReadingTime) / 60000
            alerts.add(AlertRecord(alertType = AlertRecord.SENSOR_EXPIRING, severity = "info",
                message = "已${min}分钟无血糖数据",
                action = "请检查CGM传感器连接，确保App在后台运行。"))
        }

        // 防抖过滤
        return alerts.filter { alert ->
            val key = alert.alertType
            val last = lastAlertTime[key] ?: 0
            if (now - last < 300_000) false  // 5分钟内同类不重复
            else { lastAlertTime[key] = now; true }
        }
    }

    fun calculateTIR(records: List<GlucoseRecord>): TIRResult {
        if (records.isEmpty()) return TIRResult()
        val total = records.size.toDouble()
        return TIRResult(
            inRange = records.count { it.value in LOW..HIGH } / total * 100,
            belowRange = records.count { it.value < LOW } / total * 100,
            aboveRange = records.count { it.value > HIGH } / total * 100
        )
    }

    fun calculateGRI(records: List<GlucoseRecord>): Double {
        if (records.isEmpty()) return 0.0
        val n = records.size.toDouble()
        return 3.0 * records.count { it.value < 3.0 } / n * 100 +
            2.4 * records.count { it.value in 3.0..3.9 } / n * 100 +
            1.6 * records.count { it.value in 10.0..13.9 } / n * 100 +
            0.8 * records.count { it.value > 13.9 } / n * 100
    }
}

data class TIRResult(
    val inRange: Double = 0.0,
    val belowRange: Double = 0.0,
    val aboveRange: Double = 0.0
)
