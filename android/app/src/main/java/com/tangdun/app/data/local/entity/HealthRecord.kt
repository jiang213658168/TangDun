package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 睡眠记录
 */
@Entity(
    tableName = "sleep_record",
    indices = [Index(value = ["timestamp"])]
)
data class SleepRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,           // 记录时间
    val sleepTime: Long,           // 入睡时间
    val wakeTime: Long,            // 醒来时间
    val durationMinutes: Int,      // 睡眠时长(分钟)
    val quality: String = "normal", // good/normal/poor
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 血压记录
 */
@Entity(
    tableName = "blood_pressure_record",
    indices = [Index(value = ["timestamp"])]
)
data class BloodPressureRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val systolic: Int,             // 收缩压 (mmHg)
    val diastolic: Int,            // 舒张压 (mmHg)
    val heartRate: Int? = null,    // 心率
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun getLevel(systolic: Int, diastolic: Int): String = when {
            systolic < 120 && diastolic < 80 -> "normal"
            systolic < 130 && diastolic < 80 -> "elevated"
            systolic < 140 || diastolic < 90 -> "high_1"
            systolic >= 140 || diastolic >= 90 -> "high_2"
            systolic >= 180 || diastolic >= 120 -> "crisis"
            else -> "normal"
        }

        fun getLevelName(level: String): String = when (level) {
            "normal" -> "正常"
            "elevated" -> "偏高"
            "high_1" -> "高血压1级"
            "high_2" -> "高血压2级"
            "crisis" -> "高血压危象"
            else -> "未知"
        }
    }
}

/**
 * 体重记录
 */
@Entity(
    tableName = "weight_record",
    indices = [Index(value = ["timestamp"])]
)
data class WeightRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val weightKg: Double,          // 体重 (kg)
    val heightCm: Double? = null,  // 身高 (cm)
    val bmi: Double? = null,       // BMI
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun calculateBMI(weightKg: Double, heightCm: Double): Double {
            val heightM = heightCm / 100.0
            return weightKg / (heightM * heightM)
        }

        fun getBMICategory(bmi: Double): String = when {
            bmi < 18.5 -> "underweight"
            bmi < 25.0 -> "normal"
            bmi < 30.0 -> "overweight"
            else -> "obese"
        }

        fun getBMICategoryName(category: String): String = when (category) {
            "underweight" -> "偏瘦"
            "normal" -> "正常"
            "overweight" -> "超重"
            "obese" -> "肥胖"
            else -> "未知"
        }
    }
}

/**
 * 酮体记录
 */
@Entity(
    tableName = "ketone_record",
    indices = [Index(value = ["timestamp"])]
)
data class KetoneRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val ketoneLevel: Double,       // 酮体水平 (mmol/L)
    val testType: String = "blood", // blood/urine
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun getLevel(value: Double): String = when {
            value < 0.6 -> "normal"
            value < 1.0 -> "elevated"
            value < 3.0 -> "high"
            else -> "dangerous"
        }

        fun getLevelName(level: String): String = when (level) {
            "normal" -> "正常"
            "elevated" -> "偏高"
            "high" -> "高"
            "dangerous" -> "危险"
            else -> "未知"
        }
    }
}

/**
 * 口服药记录
 */
@Entity(
    tableName = "medication_record",
    indices = [Index(value = ["timestamp"])]
)
data class MedicationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val medicationName: String,    // 药物名称
    val dose: String,              // 剂量
    val medicationType: String = "oral", // oral/injection/other
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 症状记录
 */
@Entity(
    tableName = "symptom_record",
    indices = [Index(value = ["timestamp"])]
)
data class SymptomRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val symptomType: String,       // hypo_symptom/hyper_symptom/other
    val severity: String = "mild", // mild/moderate/severe
    val symptoms: String,          // 症状描述（逗号分隔）
    val glucoseValue: Double? = null, // 当时血糖
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // 低血糖症状
        val HYPO_SYMPTOMS = listOf(
            "心慌", "手抖", "出汗", "饥饿感",
            "头晕", "乏力", "视物模糊", "意识模糊"
        )

        // 高血糖症状
        val HYPER_SYMPTOMS = listOf(
            "口渴", "多尿", "乏力", "视力模糊",
            "恶心", "腹痛", "呼吸急促", "意识模糊"
        )
    }
}
