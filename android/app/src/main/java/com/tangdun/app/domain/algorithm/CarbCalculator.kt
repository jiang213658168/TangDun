package com.tangdun.app.domain.algorithm

/**
 * 碳水计算器
 *
 * 功能：
 * 1. 计算总碳水/总热量
 * 2. 计算加权GI和GL
 * 3. 提供份量参考
 *
 * 食物营养数据现由大模型查询（FoodNutritionAi），不再使用本地数据库
 */
class CarbCalculator {

    /**
     * 营养计算结果
     */
    data class CarbResult(
        val foodName: String,
        val portionGrams: Double,
        val carbs: Double,           // 碳水 (g)
        val calories: Double,        // 热量 (kcal)
        val protein: Double,         // 蛋白质 (g)
        val fat: Double,             // 脂肪 (g)
        val gi: Double,              // GI值
        val giLevel: String,         // GI等级
        val glycemicLoad: Double,    // 血糖负荷 (GL)
        val glLevel: String          // GL等级
    )

    /**
     * 根据食物营养信息和份量计算结果
     */
    fun calculateForPortion(
        foodName: String,
        carbsPer100g: Double,
        caloriesPer100g: Double,
        proteinPer100g: Double,
        fatPer100g: Double,
        fiberPer100g: Double,
        gi: Double,
        portionGrams: Double
    ): CarbResult {
        val ratio = portionGrams / 100.0

        val carbs = carbsPer100g * ratio
        val calories = caloriesPer100g * ratio
        val protein = proteinPer100g * ratio
        val fat = fatPer100g * ratio

        // GL = (GI × 碳水g) / 100
        val glycemicLoad = (gi * carbs) / 100.0

        return CarbResult(
            foodName = foodName,
            portionGrams = portionGrams,
            carbs = carbs,
            calories = calories,
            protein = protein,
            fat = fat,
            gi = gi,
            giLevel = when {
                gi < 55 -> "low"
                gi <= 70 -> "medium"
                else -> "high"
            },
            glycemicLoad = glycemicLoad,
            glLevel = when {
                glycemicLoad < 10 -> "low"
                glycemicLoad <= 20 -> "medium"
                else -> "high"
            }
        )
    }

    /**
     * 计算加权平均GI
     */
    fun weightedGI(results: List<CarbResult>): Double {
        val totalCarbs = results.sumOf { it.carbs }
        if (totalCarbs <= 0) return 0.0
        return results.sumOf { it.gi * it.carbs } / totalCarbs
    }

    /**
     * 计算总GL
     */
    fun totalGL(results: List<CarbResult>): Double {
        return results.sumOf { it.glycemicLoad }
    }

    /**
     * GL等级中文名
     */
    fun getGLLevelName(level: String): String {
        return when (level) {
            "low" -> "低GL"
            "medium" -> "中GL"
            "high" -> "高GL"
            else -> level
        }
    }

    /**
     * GI等级中文名
     */
    fun getGiLevelName(gi: Double): String {
        return when {
            gi < 55 -> "低GI"
            gi <= 70 -> "中GI"
            else -> "高GI"
        }
    }

    /**
     * 常见食物份量参考
     */
    fun getPortionReference(foodName: String): List<Pair<String, Double>> {
        return when {
            foodName.contains("米饭") -> listOf(
                "小碗" to 100.0,
                "中碗" to 150.0,
                "大碗" to 200.0
            )
            foodName.contains("面条") -> listOf(
                "小碗" to 150.0,
                "中碗" to 250.0,
                "大碗" to 350.0
            )
            foodName.contains("馒头") -> listOf(
                "小个" to 50.0,
                "中个" to 100.0,
                "大个" to 150.0
            )
            foodName.contains("面包") -> listOf(
                "1片" to 30.0,
                "2片" to 60.0
            )
            foodName.contains("苹果") || foodName.contains("梨") -> listOf(
                "小个" to 150.0,
                "中个" to 200.0,
                "大个" to 300.0
            )
            foodName.contains("香蕉") -> listOf(
                "小根" to 80.0,
                "中根" to 120.0,
                "大根" to 150.0
            )
            else -> listOf(
                "100g" to 100.0,
                "150g" to 150.0,
                "200g" to 200.0
            )
        }
    }
}
