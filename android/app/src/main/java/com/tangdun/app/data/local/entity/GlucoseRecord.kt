package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 血糖记录实体
 *
 * 临床意义：
 * - value: 血糖值 (mmol/L)
 * - trend: 趋势方向，用于预测
 * - source: 数据来源，影响可信度
 */
@Entity(
    tableName = "glucose_record",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["source"])
    ]
)
data class GlucoseRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 测量时间（毫秒时间戳） */
    val timestamp: Long,

    /** 血糖值 (mmol/L)，临床范围 1.0-30.0 */
    val value: Double,

    /** 趋势方向 */
    val trend: String? = null,  // rising_fast/rising/stable/falling/falling_fast

    /** 数据来源 */
    val source: String = "manual",  // manual/cgm/finger/health_connect

    /** CGM传感器ID */
    val sensorId: String? = null,

    /** 原始传感器数据 */
    val rawData: Double? = null,

    /** 滤波后数据 */
    val filteredData: Double? = null,

    /** 是否已校准 */
    val isCalibrated: Boolean = false,

    /** 测量场景: fasting(空腹)/before_meal(餐前)/after_meal(餐后)/bedtime(睡前)/other */
    val scene: String = "other",

    /** 关联的饮食记录ID */
    val mealId: Long? = null,

    /** 备注 */
    val notes: String? = null,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 血糖等级 */
        fun getGlucoseLevel(value: Double): GlucoseLevel = when {
            value < 3.0 -> GlucoseLevel.SEVERE_LOW
            value < 3.9 -> GlucoseLevel.LOW
            value <= 10.0 -> GlucoseLevel.NORMAL
            value <= 13.9 -> GlucoseLevel.HIGH
            else -> GlucoseLevel.SEVERE_HIGH
        }

        /** 趋势描述 */
        fun getTrendText(trend: String?): String = when (trend) {
            "rising_fast" -> "快速上升 ↑↑"
            "rising" -> "上升 ↑"
            "stable" -> "平稳 →"
            "falling" -> "下降 ↓"
            "falling_fast" -> "快速下降 ↓↓"
            else -> "未知"
        }
    }
}

enum class GlucoseLevel(val label: String, val color: Long) {
    SEVERE_LOW("严重低血糖", 0xFFB71C1C),
    LOW("低血糖", 0xFFD32F2F),
    NORMAL("正常", 0xFF2E7D32),
    HIGH("高血糖", 0xFFE65100),
    SEVERE_HIGH("严重高血糖", 0xFFBF360C)
}
