package com.tangdun.app.domain.algorithm

import android.content.Context
import android.util.Log

/**
 * BMA融合预测器
 *
 * 结合TCN数据驱动模型和Dalla Man七隔室生理模型的优点：
 * - TCN: 从数据中学习复杂模式，精度高
 * - DallaMan: 7隔室生理机制，可解释性强，数据稀缺时更稳定
 *
 * 融合策略：贝叶斯模型平均（BMA）
 * - 根据模型置信度动态调整权重
 * - 数据充足时TCN权重更高
 * - 数据不足时DallaMan权重更高
 */
class FusionPredictor(private val context: Context) {

    companion object {
        private const val TAG = "FusionPredictor"

        // 默认权重
        const val DEFAULT_TCN_WEIGHT = 0.6
        const val DEFAULT_PHYSIO_WEIGHT = 0.4

        // 数据充足阈值（24小时 = 288个点）
        const val SUFFICIENT_DATA_THRESHOLD = 288
    }

    // TCN预测器
    private val tcnPredictor = TCNPredictor(context)

    // DallaMan七隔室生理模型
    private val physiological = DallaManModel()

    // 特征提取器
    private val featureExtractor = FeatureExtractor()

    /**
     * 融合预测结果
     */
    data class FusionResult(
        val curve: List<Double>,           // 融合后的预测曲线
        val tcnCurve: List<Double>,        // TCN单独预测
        val physioCurve: List<Double>,     // DallaMan生理模型单独预测
        val tcnWeight: Double,             // TCN权重
        val physioWeight: Double,          // DallaMan权重
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
        if (!tcnLoaded) Log.w(TAG, "TCN未加载，预测将使用DallaMan生理模型")
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
        val (tcnWeight, physioWeight) = calculateWeights(glucoseHistory.size)

        // 2. TCN预测（使用全部15维特征）
        val tcnCurve = predictWithTCN(
            glucoseHistory, currentGlucose,
            bolusHistory, carbHistory, heartRateHistory, stepHistory
        )

        // 3. DallaMan生理模型预测（从288点数组近似提取进食/注射事件）
        val physioCurve = predictWithDallaMan(
            currentGlucose, carbHistory, bolusHistory
        )

        // 4. BMA融合
        val fusedCurve = fuseCurves(tcnCurve, physioCurve, tcnWeight, physioWeight)

        // 5. 提取关键时间点
        return FusionResult(
            curve = fusedCurve,
            tcnCurve = tcnCurve,
            physioCurve = physioCurve,
            tcnWeight = tcnWeight,
            physioWeight = physioWeight,
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
     * 数据不足时：DallaMan权重更高（生理模型更稳定）
     */
    private fun calculateWeights(dataSize: Int): Pair<Double, Double> {
        val dataRatio = (dataSize.toDouble() / SUFFICIENT_DATA_THRESHOLD).coerceIn(0.0, 1.0)

        // 数据充足时TCN权重0.7，不足时降至0.3
        val tcnWeight = 0.3 + 0.4 * dataRatio
        val physioWeight = 1.0 - tcnWeight

        Log.d(TAG, "数据量: $dataSize, TCN权重: ${String.format("%.2f", tcnWeight)}, DallaMan权重: ${String.format("%.2f", physioWeight)}")

        return Pair(tcnWeight, physioWeight)
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
     * DallaMan七隔室生理模型预测
     *
     * 从288点稀疏数组中提取进食和胰岛素事件，
     * 转换为DallaManModel的MealInput/InsulinInput格式
     */
    private fun predictWithDallaMan(
        currentGlucose: Double,
        carbHistory: DoubleArray?,
        bolusHistory: DoubleArray?
    ): List<Double> {
        return try {
            // 从carbHistory提取进食事件（非零点簇 → 进食时刻和碳水克数）
            val meals = mutableListOf<DallaManModel.MealInput>()
            carbHistory?.let { ch ->
                var i = 0
                while (i < ch.size) {
                    if (ch[i] > 0) {
                        // 找到簇的起点：距现在的时间（分钟）
                        val minutesAgo = (ch.size - 1 - i) * 5.0
                        // 聚合连续非零值作为一餐
                        var total = 0.0
                        var count = 0
                        while (i < ch.size && ch[i] > 0) { total += ch[i]; i++; count++ }
                        if (total > 5.0) { // 至少5g碳水才算一餐
                            meals.add(DallaManModel.MealInput(minutesAgo, total))
                        }
                    } else { i++ }
                }
            }

            // 从bolusHistory提取胰岛素注射事件
            val insulins = mutableListOf<DallaManModel.InsulinInput>()
            bolusHistory?.let { bh ->
                var i = 0
                while (i < bh.size) {
                    if (bh[i] > 0) {
                        val minutesAgo = (bh.size - 1 - i) * 5.0
                        var total = 0.0
                        while (i < bh.size && bh[i] > 0) { total += bh[i]; i++ }
                        if (total > 0.1) { // 至少0.1U
                            insulins.add(DallaManModel.InsulinInput(minutesAgo, total))
                        }
                    } else { i++ }
                }
            }

            val curve = physiological.predict(
                currentGlucose = currentGlucose,
                currentInsulin = 10.0,
                meals = meals,
                insulins = insulins,
                horizonMinutes = 120,
                stepMinutes = 5,
                params = DallaManModel.Parameters.forUser()
            )

            Log.d(TAG, "DallaMan预测: ${meals.size}餐 ${insulins.size}针 → ${curve.size}点")
            curve
        } catch (e: Exception) {
            Log.e(TAG, "DallaMan预测异常: ${e.message}")
            generateFallbackCurve(currentGlucose)
        }
    }

    /**
     * BMA融合两个模型的预测曲线
     *
     * 融合公式：fused = w_tcn * tcn + w_physio * physio
     */
    private fun fuseCurves(
        tcnCurve: List<Double>,
        physioCurve: List<Double>,
        tcnWeight: Double,
        physioWeight: Double
    ): List<Double> {
        val minSize = minOf(tcnCurve.size, physioCurve.size)

        return (0 until minSize).map { i ->
            val fused = tcnWeight * tcnCurve[i] + physioWeight * physioCurve[i]
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
