package com.tangdun.app.domain.algorithm

import com.tangdun.app.util.SettingsManager

/**
 * 胰岛素剂量计算器
 *
 * 根据以下因素计算建议胰岛素剂量：
 * 1. 碳水化合物摄入 → 大剂量
 * 2. 当前血糖 vs 目标血糖 → 校正剂量
 * 3. 活性胰岛素(IOB) → 扣减
 *
 * 公式：
 * 总剂量 = 碳水剂量 + 校正剂量 - IOB
 * 碳水剂量 = 碳水(g) / 碳水系数
 * 校正剂量 = (当前血糖 - 目标血糖) / 胰岛素敏感因子
 */
class InsulinCalculator(private val settingsManager: SettingsManager) {

    /**
     * 计算结果
     */
    data class DoseResult(
        val totalDose: Double,          // 建议总剂量 (U)
        val carbDose: Double,           // 碳水剂量 (U)
        val correctionDose: Double,     // 校正剂量 (U)
        val iobDeduction: Double,       // IOB扣减 (U)
        val currentGlucose: Double,     // 当前血糖
        val targetGlucose: Double,      // 目标血糖
        val carbGrams: Double,          // 碳水克数
        val iob: Double,                // 活性胰岛素
        val explanation: String         // 计算说明
    )

    /**
     * 计算餐前大剂量
     *
     * @param currentGlucose 当前血糖 (mmol/L)
     * @param carbGrams 本次进食碳水 (g)
     * @param iob 活性胰岛素 (U)
     * @return 建议剂量
     */
    fun calculateBolus(
        currentGlucose: Double,
        carbGrams: Double,
        iob: Double
    ): DoseResult {
        val carbRatio = settingsManager.getCarbRatio().toDouble()
        val sensitivity = settingsManager.getInsulinSensitivity().toDouble()
        val targetGlucose = 6.0  // 目标血糖

        // 1. 碳水剂量
        val carbDose = if (carbRatio > 0) carbGrams / carbRatio else 0.0

        // 2. 校正剂量
        val correctionDose = if (currentGlucose > targetGlucose) {
            (currentGlucose - targetGlucose) / sensitivity
        } else {
            0.0
        }

        // 3. 总剂量 = 碳水 + 校正 - IOB
        val totalDose = (carbDose + correctionDose - iob).coerceAtLeast(0.0)

        // 4. 四舍五入到0.5U (kotlin.math.roundToLong 四舍五入)
        val roundedDose = kotlin.math.round(totalDose * 2) / 2.0

        // 生成说明
        val explanation = buildExplanation(
            currentGlucose, targetGlucose, carbGrams,
            carbDose, correctionDose, iob, roundedDose
        )

        return DoseResult(
            totalDose = roundedDose,
            carbDose = carbDose,
            correctionDose = correctionDose,
            iobDeduction = iob,
            currentGlucose = currentGlucose,
            targetGlucose = targetGlucose,
            carbGrams = carbGrams,
            iob = iob,
            explanation = explanation
        )
    }

    /**
     * 计算校正剂量（不进食时）
     *
     * @param currentGlucose 当前血糖
     * @param iob 活性胰岛素
     * @return 建议剂量
     */
    fun calculateCorrection(
        currentGlucose: Double,
        iob: Double
    ): DoseResult {
        val sensitivity = settingsManager.getInsulinSensitivity().toDouble()
        val targetGlucose = 6.0

        val correctionDose = (currentGlucose - targetGlucose) / sensitivity
        val totalDose = (correctionDose - iob).coerceAtLeast(0.0)
        val roundedDose = kotlin.math.round(totalDose * 2) / 2.0

        val explanation = """
            校正剂量计算:
            当前血糖: ${String.format("%.1f", currentGlucose)} mmol/L
            目标血糖: $targetGlucose mmol/L
            胰岛素敏感因子: ${String.format("%.1f", sensitivity)} mmol/L/U
            校正需要: ${String.format("%.1f", correctionDose)}U
            活性胰岛素(IOB): ${String.format("%.1f", iob)}U
            建议剂量: ${String.format("%.1f", roundedDose)}U
        """.trimIndent()

        return DoseResult(
            totalDose = roundedDose,
            carbDose = 0.0,
            correctionDose = correctionDose,
            iobDeduction = iob,
            currentGlucose = currentGlucose,
            targetGlucose = targetGlucose,
            carbGrams = 0.0,
            iob = iob,
            explanation = explanation
        )
    }

    /**
     * 生成计算说明
     */
    private fun buildExplanation(
        currentGlucose: Double,
        targetGlucose: Double,
        carbGrams: Double,
        carbDose: Double,
        correctionDose: Double,
        iob: Double,
        totalDose: Double
    ): String {
        return buildString {
            appendLine("胰岛素剂量计算:")
            appendLine("────────────────")
            appendLine("碳水: ${String.format("%.0f", carbGrams)}g")
            appendLine("碳水系数: ${settingsManager.getCarbRatio()}g/U")
            appendLine("碳水剂量: +${String.format("%.1f", carbDose)}U")
            appendLine("────────────────")
            appendLine("当前血糖: ${String.format("%.1f", currentGlucose)} mmol/L")
            appendLine("目标血糖: $targetGlucose mmol/L")
            appendLine("敏感因子: ${settingsManager.getInsulinSensitivity()} mmol/L/U")
            appendLine("校正剂量: +${String.format("%.1f", correctionDose)}U")
            appendLine("────────────────")
            appendLine("活性胰岛素(IOB): -${String.format("%.1f", iob)}U")
            appendLine("────────────────")
            appendLine("建议总剂量: ${String.format("%.1f", totalDose)}U")
        }
    }

    /**
     * 计算碳水对应的胰岛素（反向计算）
     *
     * @param insulinDose 胰岛素剂量 (U)
     * @return 可食用碳水克数
     */
    fun calculateCarbsForDose(insulinDose: Double): Double {
        val carbRatio = settingsManager.getCarbRatio().toDouble()
        return insulinDose * carbRatio
    }

    /**
     * 计算IOB衰减后的等效碳水
     *
     * @param iob 活性胰岛素 (U)
     * @return 等效碳水克数
     */
    fun iobToCarbs(iob: Double): Double {
        val carbRatio = settingsManager.getCarbRatio().toDouble()
        return iob * carbRatio
    }
}
