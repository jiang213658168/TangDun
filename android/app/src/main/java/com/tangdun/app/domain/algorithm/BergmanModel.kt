package com.tangdun.app.domain.algorithm

import kotlin.math.exp
import kotlin.math.pow

/**
 * Bergman最小模型
 *
 * 经典的血糖-胰岛素-葡萄糖相互作用模型
 * 用于What-if模拟和辅助预测
 *
 * 状态方程:
 * dG/dt = -p1*(G - Gb) - X*G + D(t)/Vg
 * dX/dt = -p2*X + p3*(I - Ib)
 * dI/dt = -n*(I - Ib) + gamma*(G - Gb)*t + U(t)/Vi
 *
 * 基于后端 app/algorithms/bergman/ 移植
 */
class BergmanModel {

    // 默认参数（来自后端 parameters.py）
    data class Parameters(
        val p1: Double = 0.03,      // 葡萄糖自身代谢率
        val p2: Double = 0.02,      // 胰岛素作用衰减率
        val p3: Double = 0.00001,   // 胰岛素敏感性
        val n: Double = 0.26,       // 胰岛素清除率
        val gamma: Double = 0.0041, // 胰岛素分泌率
        val Gb: Double = 5.0,       // 基础血糖 (mmol/L)
        val Ib: Double = 10.0,      // 基础胰岛素 (mU/L)
        val Vg: Double = 180.0,     // 葡萄糖分布容积 (dL) - 用bodyWeight重新计算
        val Vi: Double = 12.0       // 胰岛素分布容积 (L)
    ) {
        companion object {
            /** 按体重+ISF个性化 */
            fun forUser(weightKg: Double, isfEstimate: Double = 1.5) = Parameters(
                Vg = (weightKg * 1.8).coerceIn(60.0, 300.0),
                Vi = (weightKg * 0.12).coerceIn(5.0, 25.0),
                // p3(insulin sensitivity) ~ 1/ISF: ISF高(敏感)→p3大, ISF低(抵抗)→p3小
                p3 = (0.00001 * 1.5 / isfEstimate).coerceIn(0.000002, 0.00005),
                Gb = when { isfEstimate < 1.0 -> 5.5; isfEstimate > 3.0 -> 4.5; else -> 5.0 }
            )
        }
    }

    /**
     * 饮食输入
     */
    data class MealInput(
        val timeMinutes: Double,    // 时间（分钟，负数表示过去）
        val carbsGrams: Double,     // 碳水化合物 (g)
        val gi: Double = 50.0       // GI值
    )

    /**
     * 运动输入
     */
    data class ExerciseInput(
        val startMinutes: Double,   // 开始时间（分钟）
        val durationMinutes: Double, // 持续时间（分钟）
        val met: Double = 3.0       // MET值
    )

    /**
     * 预测血糖曲线
     *
     * @param currentGlucose 当前血糖 (mmol/L)
     * @param currentInsulin 当前胰岛素水平 (mU/L)
     * @param meals 饮食输入
     * @param exercises 运动输入
     * @param horizonMinutes 预测时长（分钟）
     * @param stepMinutes 步长（分钟）
     * @return 血糖曲线
     */
    fun predict(
        currentGlucose: Double,
        currentInsulin: Double = 10.0,
        meals: List<MealInput> = emptyList(),
        exercises: List<ExerciseInput> = emptyList(),
        horizonMinutes: Int = 120,
        stepMinutes: Int = 5,
        params: Parameters = Parameters()
    ): List<Double> {
        val steps = horizonMinutes / stepMinutes
        val result = mutableListOf<Double>()

        var G = currentGlucose
        var X = 0.0  // 胰岛素作用
        var I = currentInsulin

        for (step in 0..steps) {
            val t = step * stepMinutes.toDouble()
            result.add(G)

            if (step == steps) break

            // 计算饮食输入 D(t) — 加胃排空延迟(10min)
            val gastricDelay = 10.0  // 胃排空需要~10分钟
            var D = 0.0
            for (meal in meals) {
                val mealTime = -meal.timeMinutes  // 进食时刻(>0表示已过去)
                val availableTime = t - mealTime  // 食物在胃里的时间
                if (availableTime >= 0) {
                    // 胃排空修正: 前10分钟无吸收，之后指数衰减
                    val effectiveTime = maxOf(0.0, availableTime - gastricDelay)
                    val kFast = 0.2; val kSlow = 0.05; val fFast = 0.7
                    val absFast = if (effectiveTime > 0) fFast * kFast * kotlin.math.exp(-kFast * effectiveTime) else 0.0
                    val absSlow = if (effectiveTime > 0) (1 - fFast) * kSlow * kotlin.math.exp(-kSlow * effectiveTime) else 0.0
                    val glucoseFromCarbs = meal.carbsGrams * 5.56 / params.Vg
                    D += glucoseFromCarbs * (absFast + absSlow)
                }
            }

            // 计算运动影响
            var exerciseEffect = 0.0
            for (exercise in exercises) {
                if (t >= exercise.startMinutes && t <= exercise.startMinutes + exercise.durationMinutes) {
                    // 运动增加葡萄糖摄取
                    exerciseEffect += exercise.met * 0.01
                }
            }

            // 龙格-库塔法求解ODE
            val dt = stepMinutes.toDouble()

            val dG1 = -params.p1 * (G - params.Gb) - X * G + D - exerciseEffect * G
            val dX1 = -params.p2 * X + params.p3 * (I - params.Ib)
            val dI1 = -params.n * (I - params.Ib) + params.gamma * (G - params.Gb).coerceAtLeast(0.0)

            val G2 = G + dG1 * dt / 2
            val X2 = X + dX1 * dt / 2
            val I2 = I + dI1 * dt / 2

            val dG2 = -params.p1 * (G2 - params.Gb) - X2 * G2 + D - exerciseEffect * G2
            val dX2 = -params.p2 * X2 + params.p3 * (I2 - params.Ib)
            val dI2 = -params.n * (I2 - params.Ib) + params.gamma * (G2 - params.Gb).coerceAtLeast(0.0)

            val G3 = G + dG2 * dt / 2
            val X3 = X + dX2 * dt / 2
            val I3 = I + dI2 * dt / 2

            val dG3 = -params.p1 * (G3 - params.Gb) - X3 * G3 + D - exerciseEffect * G3
            val dX3 = -params.p2 * X3 + params.p3 * (I3 - params.Ib)
            val dI3 = -params.n * (I3 - params.Ib) + params.gamma * (G3 - params.Gb).coerceAtLeast(0.0)

            val G4 = G + dG3 * dt
            val X4 = X + dX3 * dt
            val I4 = I + dI3 * dt

            val dG4 = -params.p1 * (G4 - params.Gb) - X4 * G4 + D - exerciseEffect * G4
            val dX4 = -params.p2 * X4 + params.p3 * (I4 - params.Ib)
            val dI4 = -params.n * (I4 - params.Ib) + params.gamma * (G4 - params.Gb).coerceAtLeast(0.0)

            G += (dG1 + 2 * dG2 + 2 * dG3 + dG4) * dt / 6
            X += (dX1 + 2 * dX2 + 2 * dX3 + dX4) * dt / 6
            I += (dI1 + 2 * dI2 + 2 * dI3 + dI4) * dt / 6

            // 限制血糖范围
            G = G.coerceIn(1.0, 30.0)
        }

        return result
    }

    /**
     * What-if模拟
     *
     * 模拟进食后的血糖变化
     */
    fun whatIfSimulation(
        currentGlucose: Double,
        meals: List<MealInput>,
        horizonMinutes: Int = 180
    ): WhatIfResult {
        val curve = predict(
            currentGlucose = currentGlucose,
            meals = meals,
            horizonMinutes = horizonMinutes
        )

        val peak = curve.max()
        val peakIndex = curve.indexOf(peak)
        val peakTime = peakIndex * 5  // 每5分钟一个点

        // 计算低GI替代方案
        val lowGiMeals = meals.map { it.copy(gi = 35.0) }
        val lowGiCurve = predict(
            currentGlucose = currentGlucose,
            meals = lowGiMeals,
            horizonMinutes = horizonMinutes
        )
        val lowGiPeak = lowGiCurve.max()

        return WhatIfResult(
            curve = curve,
            peakGlucose = peak,
            peakTimeMinutes = peakTime,
            lowGiAlternative = lowGiCurve,
            lowGiPeak = lowGiPeak,
            peakReduction = peak - lowGiPeak
        )
    }

    data class WhatIfResult(
        val curve: List<Double>,
        val peakGlucose: Double,
        val peakTimeMinutes: Int,
        val lowGiAlternative: List<Double>,
        val lowGiPeak: Double,
        val peakReduction: Double
    )
}
