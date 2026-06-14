package com.tangdun.app.domain.algorithm

import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.entity.InsulinRecord
import com.tangdun.app.data.local.entity.MealRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 专业级自动参数估算器
 *
 * 参考商业闭环胰岛素泵的自适应算法:
 * - Medtronic SmartGuard / Tandem Control-IQ / CamAPS FX
 * - Bergman 最小模型参数辨识
 * - 1800/1500 法则 (总日剂量法)
 *
 * 核心公式:
 *   ISF = 1800 / TDD  (mg/dL → 100 / TDD mmol/L)
 *   CR  = 500 / TDD   (g per unit, 或 450 更保守)
 *   其中 TDD = 日均胰岛素总剂量
 *
 * 同时从实际数据验证:
 *   血糖降幅 / 校正剂量 → ISF 观测值
 *   碳水克数 / 大剂量 → CR 观测值
 *
 * 最终 = TDD法则 × 0.6 + 数据观测 × 0.4 (数据越多权重越高)
 */
object AutoParamEstimator {

    data class EstimatedParams(
        val insulinSensitivity: Double,  // mmol/L per unit (ISF)
        val carbRatio: Double,           // g carbs per unit (CR)
        val dailyDose: Double,           // 日均总剂量 (TDD)
        val confidence: String,
        val sampleCount: Int,
        val method: String               // 估算方法说明
    )

    fun estimate(
        glucoseRecords: List<GlucoseRecord>,
        insulinRecords: List<InsulinRecord>,
        mealRecords: List<MealRecord>
    ): EstimatedParams {

        // ── 方法1: TDD法则 (最可靠，无需配对数据) ──
        val now = System.currentTimeMillis()
        val days = maxOf(1, (insulinRecords.maxOfOrNull { it.timestamp } ?: now) -
            (insulinRecords.minOfOrNull { it.timestamp } ?: now)) / (24 * 3600 * 1000.0)
        val totalDose = insulinRecords.sumOf { it.doseUnits }
        val tdd = totalDose / maxOf(days, 1.0)  // 日均总剂量

        // TDD法则
        val isfFromTdd = if (tdd > 0) 100.0 / tdd else 1.5  // mmol/L per U
        val crFromTdd = if (tdd > 0) 450.0 / tdd else 12.0  // g per U

        // ── 方法2: 数据驱动的配对分析 ──
        val sfSamples = mutableListOf<Double>()
        val crSamples = mutableListOf<Double>()

        val sortedGlucose = glucoseRecords.sortedBy { it.timestamp }
        val sortedInsulin = insulinRecords.filter { it.insulinType == "rapid" }.sortedBy { it.timestamp }

        for (ins in sortedInsulin) {
            if (ins.doseUnits <= 0) continue

            // 注射前30分钟血糖
            val preBg = sortedGlucose.filter { it.timestamp in (ins.timestamp - 1800000)..ins.timestamp }
                .minByOrNull { abs(it.timestamp - ins.timestamp) } ?: continue

            // 注射后1-3小时最低血糖
            val postBgs = sortedGlucose.filter { it.timestamp in (ins.timestamp + 3600000)..(ins.timestamp + 10800000) }
            if (postBgs.size < 3) continue
            val minBg = postBgs.minOf { it.value }
            val drop = preBg.value - minBg

            // 检查期间是否进食
            val hasMeal = mealRecords.any { abs(it.timestamp - ins.timestamp) < 1800000 }

            if (drop > 0.5) {
                if (!hasMeal) {
                    // 纯校正大剂量 → ISF 观测值
                    sfSamples.add(drop / ins.doseUnits)
                } else {
                    // 餐时大剂量 → CR 观测值
                    val meal = mealRecords.filter { abs(it.timestamp - ins.timestamp) < 1800000 }
                        .maxByOrNull { it.totalCarbs }
                    if (meal != null && meal.totalCarbs > 0 &&
                        abs(postBgs.last().value - preBg.value) < 2.0) {  // 血糖回到基线附近
                        crSamples.add(meal.totalCarbs / ins.doseUnits)
                    }
                }
            }
        }

        // ── 融合: TDD法则 + 数据验证 ──
        val totalSamples = sfSamples.size + crSamples.size
        val dataWeight = minOf(totalSamples / 30.0, 0.5)  // 最多50%权重给数据

        val isfObserved = if (sfSamples.size >= 2)
            sfSamples.sorted().let { it[it.size / 2] }  // 中位数，抗噪声
        else isfFromTdd

        val crObserved = if (crSamples.size >= 2)
            crSamples.sorted().let { it[it.size / 2] }
        else crFromTdd

        // 最终: TDD法则(稳定) + 数据验证(个性)
        val finalIsf = isfFromTdd * (1 - dataWeight) + isfObserved * dataWeight
        val finalCr = crFromTdd * (1 - dataWeight) + crObserved * dataWeight

        val confidence = when {
            totalSamples >= 30 -> "高"
            totalSamples >= 15 -> "中"
            totalSamples >= 5 -> "低"
            else -> "仅TDD估算法"
        }

        val method = when {
            sfSamples.size >= 5 && crSamples.size >= 5 -> "TDD+配对(${sfSamples.size}/${crSamples.size}样本)"
            sfSamples.size >= 3 -> "TDD+ISF配对(${sfSamples.size}样本)"
            crSamples.size >= 3 -> "TDD+CR配对(${crSamples.size}样本)"
            else -> "TDD法则(日均${String.format("%.1f", tdd)}U)"
        }

        return EstimatedParams(
            insulinSensitivity = (finalIsf * 10).roundToInt() / 10.0,
            carbRatio = (finalCr * 10).roundToInt() / 10.0,
            dailyDose = (tdd * 10).roundToInt() / 10.0,
            confidence = confidence,
            sampleCount = totalSamples,
            method = method
        )
    }
}
