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

    fun predict(
        glucoseHistory: DoubleArray, currentGlucose: Double,
        bolusHistory: DoubleArray? = null, carbHistory: DoubleArray? = null,
        heartRateHistory: DoubleArray? = null, stepHistory: DoubleArray? = null
    ): FusionPredictor.FusionResult {
        // 1. TCN + DallaMan 基础预测
        val base = fusionPredictor.predict(
            glucoseHistory, currentGlucose,
            bolusHistory, carbHistory, heartRateHistory, stepHistory
        )

        // 2. 15维特征（供增量学习器使用）
        val features = featureExtractor.extract(
            glucoseHistory, glucoseHistory.size - 1,
            bolusHistory, carbHistory, heartRateHistory, stepHistory
        )

        // 3. 增量残差修正 (自适应: 更新数越多→权重越大)
        val residual = incrementalLearner.forward(features)
        val incUpdates = incrementalLearner.getStats()["updates"] as Int
        val hasInc = incUpdates > 20  // 降门槛: 50→20 (约10h数据即可)
        val incWeight = minOf(incUpdates.toDouble() / 300.0, 0.4)  // 300次→满权重0.4

        // 4. 时段模式
        val hourlyDev = onlineLearner.getHourlyDeviation(
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        )

        // 5. 合成曲线
        val curve = base.curve.mapIndexed { i, v ->
            var a = onlineLearner.applyPersonalization(v, currentGlucose, i)
            if (hourlyDev != 0.0) a += hourlyDev * kotlin.math.exp(-i * 5.0 / 60.0)
            if (hasInc) {
                val t = i / 24.0
                a += currentGlucose * (residual[0]*t*t*t + residual[1]*t*t + residual[2]*t + residual[3]) * incWeight
            }
            a.coerceIn(1.0, 30.0)
        }

        val label = buildString { append("BMA+个性"); if (hasInc) append("+Inc") }
        return FusionPredictor.FusionResult(
            curve = curve, tcnCurve = base.tcnCurve, physioCurve = base.physioCurve,
            tcnWeight = base.tcnWeight, physioWeight = base.physioWeight,
            predicted5min = curve.getOrNull(1), predicted15min = curve.getOrNull(3),
            predicted30min = curve.getOrNull(6), predicted60min = curve.getOrNull(12),
            predicted120min = curve.getOrNull(24), modelType = label
        )
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
}
