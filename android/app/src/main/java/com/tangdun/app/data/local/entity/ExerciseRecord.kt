package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 运动记录实体
 */
@Entity(
    tableName = "exercise_record",
    indices = [Index(value = ["startTime"])]
)
data class ExerciseRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 运动开始时间 */
    val startTime: Long,

    /** 运动结束时间 */
    val endTime: Long? = null,

    /** 运动类型 */
    val exerciseType: String = "walking",

    /** 运动时长 (分钟) */
    val durationMin: Int? = null,

    /** 平均心率 (bpm) */
    val avgHeartRate: Double? = null,

    /** 最大心率 (bpm) */
    val maxHeartRate: Double? = null,

    /** 步数 */
    val steps: Int? = null,

    /** 消耗热量 (kcal) */
    val caloriesBurned: Double? = null,

    /** 运动强度 */
    val intensity: String? = null,  // low/moderate/high

    /** 数据来源 */
    val source: String = "manual",

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 运动类型中文名 */
        fun getExerciseTypeName(type: String): String = when (type) {
            "walking" -> "步行"
            "running" -> "跑步"
            "cycling" -> "骑行"
            "swimming" -> "游泳"
            "yoga" -> "瑜伽"
            "strength" -> "力量训练"
            "aerobic" -> "有氧运动"
            "other" -> "其他"
            else -> type
        }

        /** 计算MET值 */
        fun getMET(type: String, intensity: String?): Double = when (type) {
            "walking" -> if (intensity == "high") 5.0 else 3.5
            "running" -> if (intensity == "high") 12.0 else 8.0
            "cycling" -> if (intensity == "high") 10.0 else 6.0
            "swimming" -> 7.0
            "yoga" -> 3.0
            "strength" -> 6.0
            "aerobic" -> 7.0
            else -> 4.0
        }
    }
}
