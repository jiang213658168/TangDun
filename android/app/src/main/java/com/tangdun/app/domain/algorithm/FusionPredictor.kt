package com.tangdun.app.domain.algorithm

import android.content.Context
import android.util.Log

/**
 * BMA融合预测器
 *
 * 结合TCN数据驱动模型和Bergman生理模型的优点：
 * - TCN: 从数据中学习复杂模式，精度高
 * - Bergman: 基于生理机制，可解释性强，数据稀缺时更稳定
 *
 * 融合策略：贝叶斯模型平均（BMA）
 * - 根据模型置信度动态调整权重
 * - 数据充足时TCN权重更高
 * - 数据不足时Bergman权重更高
 */
class FusionPredictor(private val context: Context) {

    companion object {
        private const val TAG = "FusionPredictor"

        // 默认权重
        const val DEFAULT_TCN_WEIGHT = 0.6
        const val DEFAULT_BERGMAN_WEIGHT = 0.4

        // 数据充足阈值（24小时 = 288个点）
        const val SUFFICIENT_DATA_THRESHOLD = 288
    }

    // TCN预测器
    private val tcnPredictor = TCNPredictor(context)

    // Bergman模型
    private val bergmanModel = BergmanModel()

    // 特征提取器
    private val featureExtractor = FeatureExtractor()

    /**
     * 融合预测结果
     */
    data class FusionResult(
        val curve: List<Double>,           // 融合后的预测曲线
        val tcnCurve: List<Double>,        // TCN单独预测
        val bergmanCurve: List<Double>,    // Bergman单独预测
        val tcnWeight: Double,             // TCN权重
        val bergmanWeight: Double,         // Bergman权重
        val predicted5min: Double?,
        val predicted15min: Double?,
        val predicted30min: Double?,
        val predicted60min: Double?,
        val predicted120min: Double?,
        val modelType: String = "BMA融合"
    )

    /**
     * 初始化模型
     */
    fun initialize(): Boolean {
        val tcnLoaded = tcnPredictor.loadModel()
        Log.i(TAG, "TCN模型加载: $tcnLoaded")
        if (!tcnLoaded) Log.w(TAG, "TCN未加载，预测将使用Bergman生理模型")
        return tcnLoaded
    }

    /**
     * 执行融合预测
     *
     * @param glucoseHistory 血糖历史（至少288点 = 24小时）
     * @param currentGlucose 当前血糖值
     * @param bolusHistory 胰岛素历史（288点）
     * @param carbHistory 碳水历史（288点）
     * @param heartRateHistory 心率历史（288点）
     * @param stepHistory 步数历史（288点）
     * @return 融合预测结果
     */
    fun predict(
        glucoseHistory: DoubleArray,
        currentGlucose: Double,
        bolusHistory: DoubleArray? = null,
        carbHistory: DoubleArray? = null,
        heartRateHistory: DoubleArray? = null,
        stepHistory: DoubleArray? = null
    ): FusionResult {
        // 1. 计算动态权重
        val (tcnWeight, bergmanWeight) = calculateWeights(glucoseHistory.size)

        // 2. TCN预测（使用全部15维特征）
        val tcnCurve = predictWithTCN(
            glucoseHistory, currentGlucose,
            bolusHistory, carbHistory, heartRateHistory, stepHistory
        )

        // 3. Bergman预测（使用碳水和胰岛素）
        val recentCarbs = carbHistory?.takeLast(48)?.sum() ?: 0.0
        val recentInsulin = bolusHistory?.takeLast(48)?.sum() ?: 0.0
        val bergmanCurve = predictWithBergman(
            currentGlucose, recentCarbs, recentInsulin, 0
        )

        // 4. BMA融合
        val fusedCurve = fuseCurves(tcnCurve, bergmanCurve, tcnWeight, bergmanWeight)

        // 5. 提取关键时间点
        return FusionResult(
            curve = fusedCurve,
            tcnCurve = tcnCurve,
            bergmanCurve = bergmanCurve,
            tcnWeight = tcnWeight,
            bergmanWeight = bergmanWeight,
            predicted5min = fusedCurve.getOrNull(1),
            predicted15min = fusedCurve.getOrNull(3),
            predicted30min = fusedCurve.getOrNull(6),
            predicted60min = fusedCurve.getOrNull(12),
            predicted120min = fusedCurve.getOrNull(24)
        )
    }

    /**
     * 计算动态权重
     *
     * 数据充足时：TCN权重更高（数据驱动模型更准）
     * 数据不足时：Bergman权重更高（生理模型更稳定）
     */
    private fun calculateWeights(dataSize: Int): Pair<Double, Double> {
        val dataRatio = (dataSize.toDouble() / SUFFICIENT_DATA_THRESHOLD).coerceIn(0.0, 1.0)

        // 数据充足时TCN权重0.7，不足时降至0.3
        val tcnWeight = 0.3 + 0.4 * dataRatio
        val bergmanWeight = 1.0 - tcnWeight

        Log.d(TAG, "数据量: $dataSize, TCN权重: ${String.format("%.2f", tcnWeight)}, Bergman权重: ${String.format("%.2f", bergmanWeight)}")

        return Pair(tcnWeight, bergmanWeight)
    }

    /**
     * TCN模型预测（使用全部15维特征）
     */
    private fun predictWithTCN(
        glucoseHistory: DoubleArray,
        currentGlucose: Double,
        bolusHistory: DoubleArray?,
        carbHistory: DoubleArray?,
        heartRateHistory: DoubleArray?,
        stepHistory: DoubleArray?
    ): List<Double> {
        return try {
            // 提取15维特征（包含胰岛素、碳水、心率、步数）
            val features = featureExtractor.extract(
                glucoseHistory, glucoseHistory.size - 1,
                bolusHistory, carbHistory, heartRateHistory, stepHistory
            )
            val result = tcnPredictor.fullPredict(features, currentGlucose)

            if (result != null) {
                Log.d(TAG, "TCN预测成功，特征维度: ${features.size}")
                result.curve
            } else {
                Log.w(TAG, "TCN预测失败，使用备用曲线")
                generateFallbackCurve(currentGlucose)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCN预测异常: ${e.message}")
            generateFallbackCurve(currentGlucose)
        }
    }

    /**
     * Bergman生理模型预测
     */
    private fun predictWithBergman(
        currentGlucose: Double,
        recentCarbs: Double,
        recentInsulin: Double,
        exerciseDuration: Int
    ): List<Double> {
        return try {
            val meals = if (recentCarbs > 0) {
                listOf(BergmanModel.MealInput(
                    timeMinutes = 0.0,
                    carbsGrams = recentCarbs,
                    gi = 50.0
                ))
            } else emptyList()

            val exercises = if (exerciseDuration > 0) {
                listOf(BergmanModel.ExerciseInput(
                    startMinutes = 0.0,
                    durationMinutes = exerciseDuration.toDouble(),
                    met = 3.0
                ))
            } else emptyList()

            val curve = bergmanModel.predict(
                currentGlucose = currentGlucose,
                currentInsulin = 10.0,
                meals = meals,
                exercises = exercises,
                horizonMinutes = 120,
                stepMinutes = 5
            )

            Log.d(TAG, "Bergman预测成功")
            curve
        } catch (e: Exception) {
            Log.e(TAG, "Bergman预测异常: ${e.message}")
            generateFallbackCurve(currentGlucose)
        }
    }

    /**
     * BMA融合两个模型的预测曲线
     *
     * 融合公式：fused = w_tcn * tcn + w_bergman * bergman
     */
    private fun fuseCurves(
        tcnCurve: List<Double>,
        bergmanCurve: List<Double>,
        tcnWeight: Double,
        bergmanWeight: Double
    ): List<Double> {
        val minSize = minOf(tcnCurve.size, bergmanCurve.size)

        return (0 until minSize).map { i ->
            val fused = tcnWeight * tcnCurve[i] + bergmanWeight * bergmanCurve[i]
            // 限制在生理范围内
            fused.coerceIn(1.0, 30.0)
        }
    }

    /**
     * 备用曲线（当两个模型都失败时）
     */
    private fun generateFallbackCurve(currentValue: Double): List<Double> {
        return (0..24).map { i ->
            val t = i / 24.0
            currentValue * (1 + 0.01 * t)
        }
    }

    /**
     * 释放资源
     */
    fun close() {
        tcnPredictor.close()
    }
}
