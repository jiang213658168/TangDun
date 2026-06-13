package com.tangdun.app.domain.algorithm

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CGM数据预处理器
 *
 * 临床意义：
 * - 卡尔曼滤波：去除传感器噪声
 * - 异常值检测：识别传感器故障
 * - 缺失值插补：处理数据丢失
 *
 * 基于后端 app/algorithms/preprocessing/cgm_preprocessor.py 移植
 */
class CGMPreprocessor {

    /**
     * 完整预处理流水线
     */
    fun preprocess(rawData: List<Double?>): List<Double> {
        // 1. 去除null值
        val cleaned = rawData.map { it ?: Double.NaN }

        // 2. 异常值检测
        val outliers = detectOutliers(cleaned)

        // 3. 缺失值插补
        val interpolated = interpolateMissing(cleaned, outliers)

        // 4. 卡尔曼滤波
        return kalmanFilter(interpolated)
    }

    /**
     * 卡尔曼滤波
     *
     * 状态模型：血糖值变化是平滑的
     * 观测模型：CGM传感器有噪声
     */
    fun kalmanFilter(data: List<Double>): List<Double> {
        if (data.isEmpty()) return emptyList()

        val result = mutableListOf<Double>()

        // 初始状态
        var x = data[0]  // 状态估计
        var p = 1.0      // 估计协方差

        // 模型参数
        val q = 0.01     // 过程噪声（血糖变化率）
        val r = 0.1      // 观测噪声（CGM传感器精度）

        for (z in data) {
            // 预测步骤
            val xPred = x  // 假设血糖值不变
            val pPred = p + q

            // 更新步骤
            val k = pPred / (pPred + r)  // 卡尔曼增益
            x = xPred + k * (z - xPred)
            p = (1 - k) * pPred

            result.add(x)
        }

        return result
    }

    /**
     * 异常值检测（MAD方法）
     *
     * 使用中位数绝对偏差检测异常值
     * 比标准差更鲁棒
     */
    fun detectOutliers(data: List<Double>): List<Boolean> {
        if (data.size < 5) return List(data.size) { false }

        val validData = data.filter { !it.isNaN() }
        if (validData.size < 5) return List(data.size) { false }

        // 计算中位数
        val sorted = validData.sorted()
        val median = sorted[sorted.size / 2]

        // 计算MAD（中位数绝对偏差）
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2] * 1.4826  // 转换为标准差估计

        // 阈值：3倍MAD
        val threshold = 3.0 * mad

        return data.map { value ->
            if (value.isNaN()) false
            else abs(value - median) > threshold
        }
    }

    /**
     * 缺失值插补
     *
     * 使用线性插值填补缺失值
     */
    fun interpolateMissing(data: List<Double>, outliers: List<Boolean>): List<Double> {
        val result = data.toMutableList()

        for (i in data.indices) {
            if (data[i].isNaN() || (i < outliers.size && outliers[i])) {
                // 寻找前后有效值
                var prevIdx = i - 1
                var nextIdx = i + 1

                while (prevIdx >= 0 && (data[prevIdx].isNaN() || (prevIdx < outliers.size && outliers[prevIdx]))) {
                    prevIdx--
                }
                while (nextIdx < data.size && (data[nextIdx].isNaN() || (nextIdx < outliers.size && outliers[nextIdx]))) {
                    nextIdx++
                }

                // 线性插值
                result[i] = when {
                    prevIdx >= 0 && nextIdx < data.size -> {
                        val ratio = (i - prevIdx).toDouble() / (nextIdx - prevIdx)
                        data[prevIdx] * (1 - ratio) + data[nextIdx] * ratio
                    }
                    prevIdx >= 0 -> data[prevIdx]
                    nextIdx < data.size -> data[nextIdx]
                    else -> 7.0  // 默认值
                }
            }
        }

        return result
    }

    /**
     * 计算血糖变化率（ROC）
     *
     * 单位：mmol/L/min
     */
    fun calculateROC(data: List<Double>, intervalMinutes: Int = 5): List<Double> {
        if (data.size < 2) return List(data.size) { 0.0 }

        return data.mapIndexed { i, _ ->
            if (i == 0) 0.0
            else (data[i] - data[i - 1]) / intervalMinutes
        }
    }

    /**
     * 计算趋势
     *
     * 基于最近15分钟的变化率
     */
    fun calculateTrend(data: List<Double>): String {
        if (data.size < 4) return "stable"

        val recentROC = (data.last() - data[data.size - 4]) / 15.0  // 15分钟ROC

        return when {
            recentROC > 0.1 -> "rising_fast"
            recentROC > 0.03 -> "rising"
            recentROC < -0.1 -> "falling_fast"
            recentROC < -0.03 -> "falling"
            else -> "stable"
        }
    }
}
