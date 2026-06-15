package com.tangdun.app.domain.algorithm

import kotlin.math.*

/**
 * Dalla Man 葡萄糖-胰岛素生理模型 (2007)
 *
 * 8个隔室, RK4积分, 商用闭环级别
 *
 * 隔室:
 *   G     - 血浆葡萄糖 (mmol/L)
 *   I     - 血浆胰岛素 (mU/L)
 *   X     - 胰岛素远端利用作用（归一化，0=无额外作用）
 *   X_L   - 胰岛素远端肝糖抑制作用（归一化，0=无抑制）
 *   stomach - 胃中碳水 (mg)
 *   gut   - 肠道碳水 (mg)
 *   subQ1 - 皮下胰岛素非单体 (mU)
 *   subQ2 - 皮下胰岛素单体 (mU)
 *
 * 参考: Dalla Man et al. "Meal Simulation Model of the Glucose-Insulin System"
 *       IEEE Trans Biomed Eng, 54(10):1740-1749, 2007
 */
class DallaManModel {

    data class Parameters(
        // ── 体重个性化 ──
        val bodyWeight: Double = 70.0,    // 体重(kg)

        // ── 胃肠道 (min⁻¹) ──
        val kStomach: Double = 0.055,     // 胃排空率
        val kGut: Double = 0.056,         // 肠道吸收率
        val fCarbs: Double = 0.9,         // 碳水生物利用度
        val VmaxGastric: Double = 10.0,   // 最大胃排空 (mg/kg/min)

        // ── 葡萄糖动力学 ──
        val VgPerKg: Double = 1.8,        // 葡萄糖分布体积 (dL/kg)
        val k1: Double = 0.065,           // 非胰岛素依赖利用率 (min⁻¹)
        val Vm0: Double = 2.5,            // 基础葡萄糖利用 (mg/kg/min, MM方程)
        val VmX: Double = 0.05,           // 胰岛素乘数 (mg/kg/min per X单位)
        val Km0: Double = 25.0,           // Michaelis常数 (mg/dL)
        val Gb: Double = 5.0,             // 基础血糖 (mmol/L)
        val Ib: Double = 10.0,            // 基础胰岛素 (mU/L)
        val renalThreshold: Double = 10.0, // 肾糖阈 (mmol/L)
        val renalClearance: Double = 0.005, // 肾清除率 (min⁻¹)

        // ── 内源性胰岛素分泌 ──
        val sigma: Double = 3.0,          // 胰腺分泌系数 (mU/L/min per mmol/L>Gb)
                                          // T1DM=0, 健康=8, T2DM=2-5

        // ── 肝糖输出 ──
        val hepaticBase: Double = 2.4,    // 基础肝糖输出 (mg/kg/min)

        // ── 胰岛素皮下吸收 (速效, min⁻¹) ──
        val ka1: Double = 0.018,          // 非单体→单体
        val ka2: Double = 0.018,          // 单体→血浆
        val ke: Double = 0.138,           // 胰岛素清除率
        val ViPerKg: Double = 0.05,       // 胰岛素分布体积 (L/kg)

        // ── 胰岛素远端作用 (min⁻¹) ──
        val kp3: Double = 0.03,           // 利用通道激活率
        val kp2: Double = 0.06            // 肝糖抑制通道激活率
    ) {
        init {
            require(bodyWeight > 0) { "体重必须>0 kg" }
            require(Ib > 0) { "基础胰岛素必须>0 mU/L" }
            require(kStomach > 0) { "胃排空率必须>0" }
            require(kGut > 0) { "肠道吸收率必须>0" }
            require(ke > 0) { "胰岛素清除率必须>0" }
            require(ViPerKg > 0) { "胰岛素分布体积必须>0" }
        }

        /** 计算个人化的分布体积 */
        val Vg: Double get() = (bodyWeight * VgPerKg).coerceIn(60.0, 300.0)
        val Vi: Double get() = (bodyWeight * ViPerKg).coerceIn(2.0, 25.0)

        companion object {
            /**
             * 中国人群参数 (默认体重65kg)
             *
             * 与西方人群的关键代谢差异：
             * - BMI较低但内脏脂肪比例更高 → 胰岛素抵抗更重
             * - β细胞功能更早衰退 → 胰岛素分泌不足更显著
             * - 碳水化合物摄入比例更高（米饭/面食为主）
             * - 餐后血糖漂移更大
             * - 肝脏葡萄糖输出略低
             *
             * 参考: China National Diabetes Survey, Li et al. 2020
             *       Bi Y et al. " metabolic profile of T2DM in Chinese", LDE 2021
             */
            /** 中国T2DM默认参数 (向后兼容) */
            fun forChinese(bodyWeight: Double = 65.0) = forUser(
                bodyWeight = bodyWeight,
                fastingGlucose = 5.2, isf = 1.5, basalInsulin = 8.0,
                sigma = 3.0, activityLevel = 0.5  // 中等活动量
            )

            /**
             * 用户个性化参数 — 全部从OnlineLearner/SettingsManager读取
             *
             * @param bodyWeight 体重kg
             * @param fastingGlucose 实测空腹血糖 (来自OnlineLearner)
             * @param isf 胰岛素敏感因子 (来自SettingsManager/AutoParamEstimator)
             * @param basalInsulin 基础胰岛素 (8.0 + 长效贡献)
             * @param sigma 内源性分泌 (ISF自适应)
             * @param activityLevel 活动水平 0( sedentary)-1(athlete), 影响k1和Vm0
             */
            fun forUser(
                bodyWeight: Double = 65.0,
                fastingGlucose: Double = 5.2,
                isf: Double = 1.5,
                basalInsulin: Double = 8.0,
                sigma: Double = 3.0,
                activityLevel: Double = 0.5
            ): Parameters {
                val isfFactor = (1.5 / isf.coerceIn(0.5, 6.0)).coerceIn(0.3, 3.0)
                return Parameters(
                bodyWeight = bodyWeight,
                // 胃肠道: 更抵抗→自主神经病变→更慢排空
                kStomach = (0.050 - isfFactor * 0.005).coerceIn(0.030, 0.055),
                kGut = 0.065, fCarbs = 0.9,
                VmaxGastric = (10.0 - isfFactor * 2.0).coerceIn(5.0, 12.0),
                sigma = sigma,
                // 葡萄糖动力学: 全部个性化
                VgPerKg = (1.6 + (bodyWeight - 65.0) * 0.01).coerceIn(1.4, 2.0),
                k1 = (0.040 + activityLevel * 0.030).coerceIn(0.035, 0.080),  // 运动多→利用高
                Vm0 = (2.5 + activityLevel * 2.0 + isfFactor * 0.5).coerceIn(2.0, 5.0),
                VmX = (0.08 + isfFactor * 0.04).coerceIn(0.04, 0.18),
                Km0 = 25.0,
                Gb = fastingGlucose,
                Ib = basalInsulin.coerceIn(4.0, 30.0),
                renalThreshold = (8.0 + fastingGlucose * 0.3).coerceIn(8.0, 12.0),
                renalClearance = 0.005,
                // 肝糖输出: ISF低(抵抗)→输出更高 (isfFactor大→抵抗)
                hepaticBase = (1.8 + isfFactor * 0.6).coerceIn(1.5, 3.0),
                ka1 = 0.018, ka2 = 0.018, ke = 0.138, ViPerKg = 0.05,
                // 胰岛素远端作用: ISF高(敏感, isfFactor小)→更快起效
                kp3 = (0.045 - isfFactor * 0.007).coerceIn(0.020, 0.050),
                kp2 = (0.060 - isfFactor * 0.007).coerceIn(0.035, 0.065)
            )
            }

            /** 西方人群参数 (文献默认, 默认体重70kg) */
            fun forWestern(bodyWeight: Double = 70.0) = Parameters(
                bodyWeight = bodyWeight,
                kStomach = 0.055, kGut = 0.056, fCarbs = 0.9, VmaxGastric = 15.0,
                sigma = 0.0,  // T1DM: 无内源性胰岛素
                VgPerKg = 1.8, k1 = 0.065, Vm0 = 2.5, VmX = 0.05, Km0 = 25.0,
                Gb = 5.0, Ib = 10.0,
                renalThreshold = 10.0, renalClearance = 0.005,
                hepaticBase = 2.4,
                ka1 = 0.018, ka2 = 0.018, ke = 0.138, ViPerKg = 0.05,
                kp3 = 0.03, kp2 = 0.06
            )
        }
    }

    data class MealInput(
        val timeMinutes: Double,    // 进食时刻(相对预测起点，正值=过去)
        val carbsGrams: Double,     // 碳水克数
        val gi: Double = 50.0       // 升糖指数（影响吸收速率，暂未使用）
    )

    data class InsulinInput(
        val timeMinutes: Double,    // 注射时刻(相对预测起点，正值=过去)
        val doseUnits: Double,      // 剂量(U)
        val type: String = "rapid"  // rapid | long
    )

    /**
     * RK4 仿真预测血糖曲线
     *
     * @param currentGlucose 当前血糖 (mmol/L)
     * @param currentInsulin 当前血浆胰岛素估算 (mU/L)
     * @param meals 近期进食（timeMinutes=距现在的分钟数，正值=过去）
     * @param insulins 近期胰岛素注射
     * @param horizonMinutes 预测时域（分钟）
     * @param stepMinutes RK4步长（分钟）
     * @param params 模型参数
     * @return 血糖曲线 (每stepMinutes一个点)
     */
    fun predict(
        currentGlucose: Double,
        currentInsulin: Double = 10.0,
        meals: List<MealInput> = emptyList(),
        insulins: List<InsulinInput> = emptyList(),
        horizonMinutes: Int = 120,
        stepMinutes: Int = 5,
        params: Parameters = Parameters()
    ): List<Double> {
        val dt = stepMinutes.toDouble()
        val steps = horizonMinutes / stepMinutes
        val result = mutableListOf<Double>()
        val weight = params.bodyWeight
        val Vg = params.Vg    // dL
        val Vi = params.Vi    // L
        val Ib = params.Ib

        // ── 初始化状态 ──
        var G = currentGlucose

        // 皮下胰岛素：从过去的注射预计算残留
        var subQ1 = 0.0
        var subQ2 = 0.0
        for (ins in insulins) {
            val T = ins.timeMinutes
            if (T < 0) continue
            // 1 U = 100 mU (近似; 实际1U ≈ 6000 pmol, 但用mU方便与I(mU/L)对接)
            val mU = ins.doseUnits * 100.0
            val ka1 = params.ka1; val ka2 = params.ka2
            // 双指数衰减：注入→subQ1→subQ2→血浆
            subQ1 += mU * exp(-ka1 * T)
            if (abs(ka2 - ka1) > 1e-6) {
                subQ2 += mU * ka1 / (ka2 - ka1) * (exp(-ka1 * T) - exp(-ka2 * T))
            } else {
                subQ2 += mU * ka1 * T * exp(-ka1 * T)
            }
        }

        // ★ 从皮下储库推算初始血浆胰岛素（避免与IOB重复计算）
        // 伪稳态: dI/dt ≈ 0 ⇒ I_ss ≈ ka2*subQ2 / (ke*Vi) + Ib
        val insFromDepot = params.ka2 * subQ2 / (params.ke * Vi)
        var I = (Ib + insFromDepot).coerceIn(Ib * 0.5, Ib * 8.0)  // 限制在生理范围
        // 若调用者提供了显著高于储库推算的currentInsulin（例如刚注射完），取其高值
        if (currentInsulin > I * 1.5) I = currentInsulin.coerceAtMost(Ib * 8.0)

        // 胰岛素远端作用：从初始I计算预激活
        var X = max(0.0, (I - Ib) / Ib).coerceIn(0.0, 3.0)
        var X_L = max(0.0, (I - Ib) / Ib).coerceIn(0.0, 3.0)

        // 胃肠道：从过去的进食预计算残留
        var stomach = 0.0
        var gut = 0.0
        for (meal in meals) {
            val T = meal.timeMinutes  // 距现在的分钟数（正值=过去）
            if (T < 0) continue       // 未来进食暂不考虑（可扩展）
            val mg = meal.carbsGrams * 1000.0 * params.fCarbs
            val kS = params.kStomach
            val kG = params.kGut
            // 一阶胃排空 + 一阶肠吸收 → 双指数求解
            stomach += mg * exp(-kS * T)
            if (abs(kG - kS) > 1e-6) {
                gut += mg * kS / (kG - kS) * (exp(-kS * T) - exp(-kG * T))
            } else {
                gut += mg * kS * T * exp(-kS * T)  // kG≈kS 极限情况
            }
        }

        // 初始gut上限: 不能超过胃排空限速下的肠道平衡容量
        val gutMax = params.VmaxGastric * weight / params.kGut
        gut = minOf(gut, gutMax)

        // ── 主仿真循环 ──
        for (step in 0..steps) {
            // 记录当前血糖
            result.add(G.coerceIn(1.0, 30.0))

            if (step == steps) break

            val t = step * dt  // 当前仿真时间 (min)

            // 检查是否有未来事件（进食/注射发生在仿真期间）
            // timeMinutes < 0 表示未来（相对预测起点之后发生）
            for (meal in meals) {
                val eventTime = -meal.timeMinutes  // 正值=未来
                if (eventTime in t..(t + dt - 1e-9)) {
                    stomach += meal.carbsGrams * 1000.0 * params.fCarbs
                }
            }
            for (ins in insulins) {
                val eventTime = -ins.timeMinutes
                if (eventTime in t..(t + dt - 1e-9)) {
                    subQ1 += ins.doseUnits * 100.0
                }
            }

            // ── RK4 一步 ──
            val s = doubleArrayOf(G, I, X, X_L, stomach, gut, subQ1, subQ2)
            val k1 = derivatives(s, params, Vg, Vi, Ib)

            val s2 = DoubleArray(8) { s[it] + 0.5 * dt * k1[it] }
            val k2 = derivatives(s2, params, Vg, Vi, Ib)

            val s3 = DoubleArray(8) { s[it] + 0.5 * dt * k2[it] }
            val k3 = derivatives(s3, params, Vg, Vi, Ib)

            val s4 = DoubleArray(8) { s[it] + dt * k3[it] }
            val k4 = derivatives(s4, params, Vg, Vi, Ib)

            // 更新状态
            G  = (s[0] + dt / 6.0 * (k1[0] + 2*k2[0] + 2*k3[0] + k4[0])).coerceIn(1.5, 28.0)
            I  = max(0.5, s[1] + dt / 6.0 * (k1[1] + 2*k2[1] + 2*k3[1] + k4[1]))
            X  = (s[2] + dt / 6.0 * (k1[2] + 2*k2[2] + 2*k3[2] + k4[2])).coerceIn(0.0, 5.0)
            X_L = (s[3] + dt / 6.0 * (k1[3] + 2*k2[3] + 2*k3[3] + k4[3])).coerceIn(0.0, 5.0)
            stomach = max(0.0, s[4] + dt / 6.0 * (k1[4] + 2*k2[4] + 2*k3[4] + k4[4]))
            gut   = max(0.0, s[5] + dt / 6.0 * (k1[5] + 2*k2[5] + 2*k3[5] + k4[5]))
            subQ1 = max(0.0, s[6] + dt / 6.0 * (k1[6] + 2*k2[6] + 2*k3[6] + k4[6]))
            subQ2 = max(0.0, s[7] + dt / 6.0 * (k1[7] + 2*k2[7] + 2*k3[7] + k4[7]))
        }

        return result
    }

    /**
     * 计算8状态ODE系统的导数
     *
     * state[0]=G, [1]=I, [2]=X, [3]=X_L, [4]=stomach, [5]=gut, [6]=subQ1, [7]=subQ2
     */
    private fun derivatives(
        state: DoubleArray,
        p: Parameters, Vg: Double, Vi: Double, Ib: Double
    ): DoubleArray {
        val G = state[0]; val I = state[1]
        val X = state[2]; val X_L = state[3]
        val stomach = state[4]; val gut = state[5]
        val subQ1 = state[6]; val subQ2 = state[7]

        val weight = p.bodyWeight
        val VgDl18 = Vg * 18.0  // 转换因子: Vg(dL) × 18(mg/dL per mmol/L)

        // ── dG/dt: 血浆葡萄糖变化 ──
        // Ra: 肠道葡萄糖吸收入血 (mmol/L/min)
        val RaMmol = p.kGut * gut / VgDl18

        // EGP: 肝糖内生产出 (mg/kg/min → mmol/L/min)
        // X_L 越大→抑制越强→EGP越小
        val hepaticSuppression = X_L / (1.0 + X_L)  // [0, 1), 单调递增
        val EGP_mgPerKgPerMin = p.hepaticBase * (1.0 - hepaticSuppression)
        val EGPmmol = EGP_mgPerKgPerMin * weight / VgDl18

        // Uii: 非胰岛素依赖利用（中枢神经系统等），一阶趋近基础
        val Uiimmol = p.k1 * (G - p.Gb)

        // Uid: 胰岛素依赖利用 — Michaelis-Menten饱和动力学
        // 比线性k2*X*G更符合生理: 高血糖时利用饱和，不无限增加
        val G_mg_dL = G * 18.0  // mmol/L → mg/dL
        val Vm = p.Vm0 + p.VmX * X  // 最大利用速率 (mg/kg/min), 胰岛素促进
        val Uid_mgKgMin = Vm * G_mg_dL / (p.Km0 + G_mg_dL)  // MM方程
        val Uidmmol = Uid_mgKgMin * weight / VgDl18  // → mmol/L/min

        // 肾脏排泄
        val renalMmol = if (G > p.renalThreshold) {
            p.renalClearance * (G - p.renalThreshold)
        } else 0.0

        val dG = RaMmol + EGPmmol - Uiimmol - Uidmmol - renalMmol

        // ── dI/dt: 血浆胰岛素变化 (mU/L/min) ──
        // 从皮下单体隔室吸收入血
        val insAppearance = p.ka2 * subQ2 / Vi   // mU/L/min
        val insClearance  = p.ke * I              // mU/L/min
        // 内源性胰岛素分泌: 血糖高于基础时胰腺分泌 (T2DM保留部分功能)
        val endogenous = p.sigma * maxOf(0.0, G - p.Gb)  // mU/L/min
        val dI = insAppearance - insClearance + endogenous

        // ── dX/dt: 胰岛素远端利用作用 ──
        // 由高于基础的胰岛素驱动，kp3控制激活速率
        val insulinDrive = max(0.0, I - Ib) / Ib  // 归一化驱动
        val dX = -p.kp3 * X + p.kp3 * insulinDrive

        // ── dX_L/dt: 胰岛素远端肝糖抑制作用 ──
        val dX_L = -p.kp2 * X_L + p.kp2 * insulinDrive

        // ── d(stomach)/dt: 胃排空 (速率上限防止大餐数据爆炸) ──
        val gastricRate = p.kStomach * stomach
        val gastricMax = p.VmaxGastric * weight  // mg/min 最大排空
        val dStomach = -minOf(gastricRate, gastricMax)

        // ── d(gut)/dt: 肠道传输与吸收 ──
        val dGut = minOf(gastricRate, gastricMax) - p.kGut * gut

        // ── d(subQ1)/dt: 皮下非单体→单体 ──
        val dSubQ1 = -p.ka1 * subQ1

        // ── d(subQ2)/dt: 单体生成 - 吸收入血 ──
        val dSubQ2 = p.ka1 * subQ1 - p.ka2 * subQ2

        return doubleArrayOf(dG, dI, dX, dX_L, dStomach, dGut, dSubQ1, dSubQ2)
    }

    /**
     * What-if模拟：模拟进食后的血糖变化
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

        // 计算低GI替代方案（减慢胃排空速率来模拟低GI）
        val lowGiParams = Parameters(kStomach = 0.035, kGut = 0.040)
        val lowGiCurve = predict(
            currentGlucose = currentGlucose,
            meals = meals,
            horizonMinutes = horizonMinutes,
            params = lowGiParams
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
