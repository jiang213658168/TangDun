package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 预警记录实体
 */
@Entity(
    tableName = "alert_record",
    indices = [Index(value = ["createdAt"])]
)
data class AlertRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 预警类型 */
    val alertType: String,

    /** 严重程度 */
    val severity: String = "warning",  // critical/warning/info

    /** 血糖值 */
    val glucoseValue: Double? = null,

    /** 预测值 */
    val predictedValue: Double? = null,

    /** 预警消息 */
    val message: String? = null,

    /** 处理建议 */
    val action: String? = null,

    /** 是否已读 */
    val isRead: Boolean = false,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 预警类型 */
        const val LOW_GLUCOSE = "low_glucose"
        const val HIGH_GLUCOSE = "high_glucose"
        const val SEVERE_LOW = "severe_low"
        const val SEVERE_HIGH = "severe_high"
        const val RAPID_RISE = "rapid_rise"
        const val RAPID_FALL = "rapid_fall"
        const val PREDICTED_LOW = "predicted_low"
        const val PREDICTED_HIGH = "predicted_high"
        const val INSULIN_STACKING = "insulin_stacking"
        const val SENSOR_EXPIRING = "sensor_expiring"

        /** 获取预警类型中文名 */
        fun getAlertTypeName(type: String): String = when (type) {
            LOW_GLUCOSE -> "低血糖预警"
            HIGH_GLUCOSE -> "高血糖预警"
            SEVERE_LOW -> "严重低血糖"
            SEVERE_HIGH -> "严重高血糖"
            RAPID_RISE -> "血糖快速上升"
            RAPID_FALL -> "血糖快速下降"
            PREDICTED_LOW -> "预测低血糖"
            PREDICTED_HIGH -> "预测高血糖"
            INSULIN_STACKING -> "胰岛素叠加"
            SENSOR_EXPIRING -> "传感器即将过期"
            else -> "预警"
        }

        /** 获取处理建议 */
        fun getAction(type: String, glucoseValue: Double?): String = when (type) {
            LOW_GLUCOSE -> {
                if (glucoseValue != null && glucoseValue < 3.0) {
                    "严重低血糖！立即补充15-20g快速碳水（葡萄糖片、果汁），15分钟后复查"
                } else {
                    "补充15g快速碳水（葡萄糖片、果汁、糖果），15分钟后复查血糖"
                }
            }
            SEVERE_LOW -> "严重低血糖！立即补充20g快速碳水，如意识不清请立即拨打120"
            HIGH_GLUCOSE -> {
                if (glucoseValue != null && glucoseValue > 13.9) {
                    "血糖严重偏高，请检测酮体，考虑补充胰岛素，多喝水"
                } else {
                    "多喝水，适量运动，1小时后复查血糖"
                }
            }
            SEVERE_HIGH -> "血糖严重偏高！请检测酮体，如酮体阳性请立即就医"
            RAPID_RISE -> "血糖快速上升，关注是否进食过多碳水或胰岛素不足"
            RAPID_FALL -> "血糖快速下降，注意预防低血糖，可适当补充碳水"
            PREDICTED_LOW -> "预测30分钟内可能出现低血糖，建议提前补充碳水"
            PREDICTED_HIGH -> "预测血糖将持续偏高，注意饮食和胰岛素"
            INSULIN_STACKING -> "胰岛素叠加风险，两次注射间隔过短，注意监测低血糖"
            else -> "请继续监测血糖"
        }
    }
}
