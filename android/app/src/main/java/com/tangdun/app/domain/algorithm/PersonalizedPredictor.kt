package com.tangdun.app.domain.algorithm

import android.content.Context
import android.util.Log
import com.tangdun.app.data.local.dao.GlucoseDao

/**
 * 个性化预测器 — 四层自进化架构
 *
 * 1. TCN (ONNX, MAE 0.552) → 15维特征 → 曲线参数 [a,b,c,d]
 * 2. DallaMan 七隔室生理模型 → RK4 ODE → 物理约束曲线
 * 3. OnlineLearner → 统计学习 (EWMA/卡尔曼/贝叶斯/时段模式)
 * 4. IncrementalLearner → TCN残差 SGD 在线学习 (304参数)
 *
 * BMA融合: TCN_w + DallaMan_w = 1.0, 数据充足→TCN权重高
 */
class PersonalizedPredictor(private val context: Context) {

    companion object { private const val TAG = "PersonalizedPred" }

    private val fusionPredictor = FusionPredictor(context)
    private val onlineLearner = OnlineLearner(context)
    private val incrementalLearner = IncrementalLearner(context)
    private val featureExtractor = FeatureExtractor()

    fun initialize(): Boolean {
        val ok = fusionPredictor.initialize()
        Log.i(TAG, "TCN=${if (ok) "ONNX" else "FAIL"}, " +
            "Online=${onlineLearner.getStageDescription()}, " +
            "Incremental=${incrementalLearner.getStats()["updates"]}次")
        return ok
    }

    /**
     * TCN 单独预测 (只返回 TCN 曲线, 不叠加任何修正)
     * 供 PredictionViewModel 在主路径之外单独拿 TCN 输出用于 BMA 融合
     */
    fun predictTCNOnly(
        glucoseHistory: DoubleArray, currentGlucose: Double,
        bolusHistory: DoubleArray? = null, carbHistory: DoubleArray? = null,
        heartRateHistory: DoubleArray? = null, stepHistory: DoubleArray? = null
    ): List<Double>? {
        return try {
            val features = featureExtractor.extract(
                glucoseHistory, glucoseHistory.size - 1,
                bolusHistory, carbHistory, heartRateHistory, stepHistory
            )
            fusionPredictor.predictTCNDirect(features, currentGlucose)
        } catch (e: Exception) {
            Log.w(TAG, "predictTCNOnly 失败: ${e.message}")
            null
        }
    }

    /**
     * ★ v3.0.14 修复 TCN vs 生理底层冲突
     *
     * 之前的 bug:
     *   - Path A (主) 跑 DallaMan 用 finalParams (用户真实体重/ISF/CR)
     *   - Path B (TCN对比) 跑 DallaMan 用 Parameters.forUser() 默认 (65kg/ISF 1.5)
     *   - 两套参数 → 两条 DallaMan 曲线不同 → TCN 看到的"参考基线"跟 Path A 不一致
     *   → 看起来"TCN 和生理方向冲突"，实际是 Path B 的生理基线本身错了
     *
     * 现在:
     *   - Path A 算出的 physioCurve 直接传入, Path B 不再重复算 DallaMan
     *   - TCN 拿到的对比基线 100% 跟 Path A 一致
     *   - 真正反映"TCN vs 真实生理"的方向差
     */
    fun predictWithTCNBaseline(
        glucoseHistory: DoubleArray, currentGlucose: Double,
        physioBaseline: List<Double>,
        bolusHistory: DoubleArray? = null, carbHistory: DoubleArray? = null
    ): Pair<List<Double>?, List<Double>> {
        val tcnCurve = predictTCNOnly(glucoseHistory, currentGlucose, bolusHistory, carbHistory)
        return Pair(tcnCurve, physioBaseline)
    }

    /**
     * 对给定基础曲线叠加个性化 + 增量残差修正
     *
     * @param baseCurve 基础曲线 (通常是 Path A 的 DallaMan 生理曲线, 已带 EDOC 修正)
     * @param currentGlucose 当前血糖, 用于残差相对幅度计算
     * @param glucoseHistory 血糖历史 (供特征提取)
     * @param bolusHistory 胰岛素历史
     * @param carbHistory 碳水历史
     * @return 叠加了 OnlineLearner 个性化 + 增量网络残差的曲线
     */
    fun applyPersonalization(
        baseCurve: List<Double>, currentGlucose: Double,
        glucoseHistory: DoubleArray,
        bolusHistory: DoubleArray? = null, carbHistory: DoubleArray? = null,
        heartRateHistory: DoubleArray? = null, stepHistory: DoubleArray? = null
    ): List<Double> {
        if (baseCurve.isEmpty()) return baseCurve

        // 1. 15维特征 (供增量学习器使用)
        val features = featureExtractor.extract(
            glucoseHistory, glucoseHistory.size - 1,
            bolusHistory, carbHistory, heartRateHistory, stepHistory
        )

        // 2. 增量残差修正 (自适应: 更新数越多→权重越大)
        val residual = incrementalLearner.forward(features)
        val incUpdates = incrementalLearner.getStats()["updates"] as Int
        val hasInc = incUpdates > 20
        val incWeight = minOf(incUpdates.toDouble() / 300.0, 0.4)

        // 3. 时段模式
        val hourlyDev = onlineLearner.getHourlyDeviation(
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        )

        // 4. 合成: 基础曲线 + 个性化偏移 + 时段模式 + 残差
        //   ★ v3.0.9 修复: t 必须按 baseCurve 实际时间跨度归一化到 [0, 1]
        //   之前硬编码 t = i/24.0 在 baseCurve=36 点 (180min) 时 t 最大 1.46 → 多项式爆炸
        //   现在 t = i / (n - 1) 保证 [0, 1]
        val n = baseCurve.size
        val tMax = if (n > 1) (n - 1).toDouble() else 1.0
        return baseCurve.mapIndexed { i, v ->
            var a = onlineLearner.applyPersonalization(v, currentGlucose, i)
            if (hourlyDev != 0.0 && i < n) a += hourlyDev * kotlin.math.exp(-i * 5.0 / 60.0)
            if (hasInc) {
                val t = i / tMax
                // 安全保护: t 范围外 clamp 到 [0, 1]
                val tClamped = t.coerceIn(0.0, 1.0)
                a += currentGlucose * (residual[0]*tClamped*tClamped*tClamped + residual[1]*tClamped*tClamped + residual[2]*tClamped + residual[3]) * incWeight
            }
            a.coerceIn(1.0, 30.0)
        }
    }

    suspend fun learn(glucoseDao: GlucoseDao): Boolean {
        onlineLearner.learn(glucoseDao)
        incrementalLearner.periodicLearn(glucoseDao)
        return true
    }

    fun getIncrementalStats() = incrementalLearner.getStats()
    fun getLearningStatus() = mapOf(
        "stage" to onlineLearner.getLearningStage().name,
        "data_days" to onlineLearner.getPersonalParams().dataDays,
        "incremental" to incrementalLearner.getStats()
    )
    fun close() { fusionPredictor.close() }

    /** 仅供 PersonalizedPredictor 内部使用, 暴露 fusionPredictor 引用 */
    internal val fusionPredictorInternal get() = fusionPredictor
}
