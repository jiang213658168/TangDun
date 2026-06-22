package com.tangdun.app.domain.algorithm

import android.content.Context
import android.util.Log
import com.tangdun.app.data.local.dao.GlucoseDao

/**
 * 个性化预测器 — 四层自进化架构
 *
 * 1. TCN (ONNX, MAE 0.612, Clarke A 92.5%) → 15维特征 → 曲线参数 [a,b,c,d]
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

    // ★ v3.0.16 删除: applyPersonalization(baseCurve, ...) 是 dead code (全工程无 caller)
    //   PredictionViewModel:193 直接用 onlineLearner.applyPersonalization, 不走这里
    //   完整功能 (OnlineLearner + 时段模式 + 残差) 已由 PredictionViewModel.applyLightPersonalization 替代

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
