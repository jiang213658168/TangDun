package com.tangdun.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 饮食记录实体
 */
@Entity(
    tableName = "meal_record",
    indices = [Index(value = ["timestamp"])]
)
data class MealRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 进食时间 */
    val timestamp: Long,

    /** 餐型 */
    val mealType: String? = null,  // breakfast/lunch/dinner/snack

    /** 拍照图片路径 */
    val imagePath: String? = null,

    /** 总碳水化合物 (g) */
    val totalCarbs: Double = 0.0,

    /** 总热量 (kcal) */
    val totalCalories: Double = 0.0,

    /** 总蛋白质 (g) */
    val totalProtein: Double = 0.0,

    /** 总脂肪 (g) */
    val totalFat: Double = 0.0,

    /** 总膳食纤维 (g) */
    val totalFiber: Double = 0.0,

    /** 加权平均GI */
    val avgGi: Double = 0.0,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 自动判断餐型 */
        fun inferMealType(hour: Int): String = when (hour) {
            in 5..9 -> "breakfast"
            in 10..11 -> "morning_snack"
            in 11..13 -> "lunch"
            in 14..16 -> "afternoon_snack"
            in 17..19 -> "dinner"
            in 20..22 -> "evening_snack"
            else -> "snack"
        }

        /** 餐型中文名 */
        fun getMealTypeName(type: String?): String = when (type) {
            "breakfast" -> "早餐"
            "morning_snack" -> "上午加餐"
            "lunch" -> "午餐"
            "afternoon_snack" -> "下午加餐"
            "dinner" -> "晚餐"
            "evening_snack" -> "晚间加餐"
            "snack" -> "加餐"
            else -> "饮食"
        }
    }
}

/**
 * 饮食明细实体
 */
@Entity(
    tableName = "meal_item",
    foreignKeys = [
        ForeignKey(
            entity = MealRecord::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["mealId"])]
)
data class MealItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 关联的饮食记录ID */
    val mealId: Long,

    /** 食物名称 */
    val foodName: String,

    /** 份量 (g) */
    val portionGrams: Double = 100.0,

    /** 碳水化合物 (g) */
    val carbs: Double = 0.0,

    /** 热量 (kcal) */
    val calories: Double = 0.0,

    /** 蛋白质 (g) */
    val protein: Double = 0.0,

    /** 脂肪 (g) */
    val fat: Double = 0.0,

    /** 膳食纤维 (g) */
    val fiber: Double = 0.0,

    /** GI值 */
    val gi: Double = 0.0,

    /** 识别置信度 */
    val recognitionConfidence: Double? = null
)
