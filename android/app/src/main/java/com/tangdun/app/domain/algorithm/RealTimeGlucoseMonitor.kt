package com.tangdun.app.domain.algorithm

import android.content.Context
import android.util.Log
import com.tangdun.app.data.local.entity.GlucoseRecord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * 实时血糖监测引擎 — 参考 xDrip+ BgReading + BgGraphBuilder 算法
 *
 * 核心流程:
 *   原始值 → 卡尔曼滤波 → 噪声检测 → 趋势计算 → 质量评分 → 输出
 *
 * 参考:
 *   - xDrip+ BgGraphBuilder.noiseCalculator (多项式拟合误差方差)
 *   - xDrip+ BgReading.calculateSlope / find_new_curve (抛物线拟合)
 *   - xDrip+ GotoSmoother (Savitzky-Golay 卷积平滑)
 *   - xDrip+ BgReading.injectNoise (噪声分级 0-4)
 *
 * 特性:
 *   - 多源输入统一接口 (通知监听/广播/手动)
 *   - 卡尔曼滤波降噪
 *   - 多项式拟合噪声检测 (error variance)
 *   - 线性回归趋势 + 置信度
 *   - 数据缺失检测 + 间隙插值
 *   - 智能去重 (时间+来源感知)
 *   - Flow 事件输出
 */
class RealTimeGlucoseMonitor(private val context: Context) {

    companion object {
        private const val TAG = "RTGlucose"
        private const val MIN_GLUCOSE = 2.2          // mmol/L 最低合理值
        private const val MAX_GLUCOSE = 22.0         // mmol/L 最高合理值
        private const val MIN_READING_INTERVAL_MS = 55_000L  // 去重: 55秒内
        private const val GAP_THRESHOLD_MS = 15 * 60_000L    // 超过15分钟视为数据缺失
        private const val STALE_THRESHOLD_MS = 30 * 60_000L  // 超过30分钟视为陈旧

        // 噪声分级阈值 (参考 xDrip+ NOISE_TRIGGER 等)
        const val NOISE_CLEAN = 0       // 无噪声 (< 2.0)
        const val NOISE_LIGHT = 1       // 轻微 (2.0 - 5.0)
        const val NOISE_MODERATE = 2    // 中等 (5.0 - 10.0)
        const val NOISE_HIGH = 3        // 较高 (10.0 - 60.0)
        const val NOISE_EXTREME = 4     // 极高 (> 60.0)
    }

    // ── 状态 ──
    private val preprocessor = CGMPreprocessor()
    private val calibrator = CGMCalibrator(context)
    private val trendCalc = TrendCalculator()

    // 滑动窗口: 最近30个已处理读数（2.5小时 @5min间隔）
    private val recentReadings = ArrayDeque<ProcessedReading>(30)

    // ── 输出 ──
    private val _processedFlow = MutableSharedFlow<ProcessedReading>(replay = 1)
    val processedFlow: SharedFlow<ProcessedReading> = _processedFlow

    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState

    /** 处理结果 */
    data class ProcessedReading(
        val timestamp: Long,           // 毫秒时间戳
        val rawValue: Double,          // 原始值 (mmol/L)
        val filteredValue: Double,     // 卡尔曼滤波后 (mmol/L)
        val calibratedValue: Double,   // 指尖血校准后 (mmol/L)
        val roc: Double,               // 变化率 (mmol/L/min)
        val noiseLevel: Int,           // 噪声等级 0-4
        val noiseVariance: Double,     // 噪声方差
        val qualityScore: Int,         // 质量评分 0-100
        val trend: TrendCalculator.Trend,  // 趋势方向
        val isStale: Boolean,          // 是否陈旧
        val isGap: Boolean,            // 前一个间隔是否有间隙
        val source: String,            // 数据来源
        val sensorAge: Long? = null    // 传感器已用时长(ms)，未知=null
    )

    /** 监测状态 */
    data class MonitorState(
        val lastReadingTime: Long = 0,
        val readingCount: Int = 0,
        val averageInterval: Double = 0.0,    // 平均采样间隔(分钟)
        val dataQuality: Int = 0,             // 数据质量 0-100
        val isStale: Boolean = true,
        val consecutiveGaps: Int = 0,
        val calibrationNeeded: Boolean = false
    )

    /**
     * 输入新血糖值 — 统一入口
     *
     * @param rawMmol 原始血糖值 (mmol/L)
     * @param timestamp 时间戳 (ms)
     * @param source 来源 (cgm_notify / xdrip / manual)
     * @param rawData 原始传感器数据 (可选)
     * @param sensorAge 传感器已用时长 (可选)
     */
    fun ingest(
        rawMmol: Double,
        timestamp: Long = System.currentTimeMillis(),
        source: String = "unknown",
        rawData: Double? = null,
        sensorAge: Long? = null
    ): ProcessedReading? {
        // ── 1. 合理性检查 ──
        if (rawMmol !in MIN_GLUCOSE..MAX_GLUCOSE) {
            Log.w(TAG, "值超出合理范围: $rawMmol → 丢弃")
            return null
        }

        // ── 2. 未来时间检查 ──
        val now = System.currentTimeMillis()
        if (timestamp > now + 60_000) {
            Log.w(TAG, "未来时间戳 → 修正为当前时间")
            // 不回退，使用当前时间
        }
        val correctedTs = minOf(timestamp, now)

        // ── 3. 智能去重: 同源55秒内 → 跳过 ──
        val lastReading = recentReadings.lastOrNull()
        if (lastReading != null) {
            val sameSource = lastReading.source == source
            val tooClose = abs(correctedTs - lastReading.timestamp) < MIN_READING_INTERVAL_MS
            val sameValue = abs(rawMmol - lastReading.rawValue) < 0.05

            if (sameSource && tooClose) {
                Log.d(TAG, "去重: 同源${abs(correctedTs - lastReading.timestamp)/1000}s内 → 跳过")
                return null
            }
            // 同值去重 (跨源快速重复)
            if (tooClose && sameValue) {
                Log.d(TAG, "去重: 同值重复 → 跳过")
                return null
            }
        }

        // ── 4. 间隙检测 ──
        val isGap = lastReading != null &&
            (correctedTs - lastReading.timestamp) > GAP_THRESHOLD_MS
        val isStale = lastReading != null &&
            (now - lastReading.timestamp) > STALE_THRESHOLD_MS

        // ── 5. 构建滑动窗口 (最近30个原始值) ──
        val window = recentReadings.map { it.filteredValue }.takeLast(30).toMutableList()
        window.add(rawMmol)
        if (window.size > 30) window.removeAt(0)

        // ── 6. 卡尔曼滤波 ──
        val filtered = preprocessor.kalmanFilter(window).last()

        // ── 7. 指尖血校准 ──
        val calibrated = calibrator.applyCalibration(filtered)

        // ── 8. 噪声检测 (多项式拟合误差方差) ──
        val (noiseLevel, noiseVar) = detectNoise(window)

        // ── 9. 变化率计算 (线性回归) ──
        val roc = calculateROC(recentReadings, rawMmol, correctedTs)

        // ── 10. 趋势分类 ──
        val trend = classifyTrend(roc, noiseLevel)

        // ── 11. 质量评分 ──
        val qualityScore = calculateQualityScore(noiseLevel, isStale, isGap, roc)

        // ── 12. 组装输出 ──
        val reading = ProcessedReading(
            timestamp = correctedTs,
            rawValue = rawMmol,
            filteredValue = filtered,
            calibratedValue = calibrated,
            roc = roc,
            noiseLevel = noiseLevel,
            noiseVariance = noiseVar,
            qualityScore = qualityScore,
            trend = trend,
            isStale = isStale,
            isGap = isGap,
            source = source,
            sensorAge = sensorAge
        )

        // ── 13. 更新状态 ──
        recentReadings.add(reading)
        if (recentReadings.size > 30) recentReadings.removeFirst()

        val avgInterval = if (recentReadings.size >= 2) {
            val intervals = recentReadings.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 60000.0 }
            intervals.average()
        } else 0.0

        _monitorState.value = MonitorState(
            lastReadingTime = correctedTs,
            readingCount = _monitorState.value.readingCount + 1,
            averageInterval = avgInterval,
            dataQuality = if (recentReadings.size >= 5)
                recentReadings.takeLast(10).map { it.qualityScore }.average().toInt()
            else qualityScore,
            isStale = isStale,
            consecutiveGaps = if (isGap) _monitorState.value.consecutiveGaps + 1 else 0,
            calibrationNeeded = calibrator.needsCalibration()
        )

        _processedFlow.tryEmit(reading)
        Log.d(TAG, "摄入: raw=${"%.1f".format(rawMmol)} filt=${"%.1f".format(filtered)} " +
            "roc=${"%.3f".format(roc)} noise=$noiseLevel quality=$qualityScore $source")
        return reading
    }

    /**
     * 噪声检测 — 参考 xDrip+ BgGraphBuilder.noiseCalculator
     *
     * 使用3次多项式拟合最近数据点，计算拟合误差方差
     * 误差方差越大 → 数据越嘈杂 → 噪声等级越高
     */
    private fun detectNoise(window: List<Double>): Pair<Int, Double> {
        if (window.size < 6) return Pair(NOISE_CLEAN, 0.0)

        val n = window.size
        // 构建时间序列 x = [0, 1, 2, ..., n-1]
        val xs = DoubleArray(n) { it.toDouble() }
        val ys = window.toDoubleArray()

        // 3次多项式最小二乘拟合: y = a0 + a1*x + a2*x^2 + a3*x^3
        // 构建正规方程: (X^T X) b = X^T y
        val degree = minOf(3, n - 1)
        val coeffs = polyFit(xs, ys, degree) ?: return Pair(NOISE_CLEAN, 0.0)

        // 计算残差方差 (error variance)
        var errorVar = 0.0
        for (i in 0 until n) {
            val predicted = polyEval(coeffs, xs[i])
            val residual = ys[i] - predicted
            errorVar += residual * residual
        }
        errorVar /= n

        // 映射到噪声等级 (参考 xDrip+ 的阈值: 2, 5, 10, 60)
        val level = when {
            errorVar < 2.0 -> NOISE_CLEAN
            errorVar < 5.0 -> NOISE_LIGHT
            errorVar < 10.0 -> NOISE_MODERATE
            errorVar < 60.0 -> NOISE_HIGH
            else -> NOISE_EXTREME
        }

        return Pair(level, errorVar)
    }

    /** 多项式拟合 — 最小二乘法 (正规方程) */
    private fun polyFit(xs: DoubleArray, ys: DoubleArray, degree: Int): DoubleArray? {
        val n = xs.size
        if (n <= degree) return null

        // 构建 Vandermonde 矩阵的转置 × Vandermonde 矩阵
        val m = degree + 1
        val XTX = Array(m) { DoubleArray(m) }
        val XTy = DoubleArray(m)

        for (i in 0 until n) {
            var xiPow = 1.0
            for (j in 0 until m) {
                XTy[j] += xiPow * ys[i]
                var xkPow = 1.0
                for (k in 0 until m) {
                    XTX[j][k] += xiPow * xkPow
                    xkPow *= xs[i]
                }
                xiPow *= xs[i]
            }
        }

        // 高斯消元求解
        return solveLinearSystem(XTX, XTy)
    }

    private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = A.size
        val aug = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] } }

        for (col in 0 until n) {
            // 部分主元
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
            }
            if (abs(aug[maxRow][col]) < 1e-10) return null
            val temp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = temp

            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col..n) aug[row][j] -= factor * aug[col][j]
            }
        }

        // 回代
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) x[i] -= aug[i][j] * x[j]
            x[i] /= aug[i][i]
        }
        return x
    }

    private fun polyEval(coeffs: DoubleArray, x: Double): Double {
        var result = 0.0
        var xPow = 1.0
        for (c in coeffs) { result += c * xPow; xPow *= x }
        return result
    }

    /**
     * 变化率计算 — 参考 xDrip+ BgReading.find_new_curve()
     *
     * 使用最近6个数据点的线性回归斜率
     * 不足6个点时使用简单差分
     */
    private fun calculateROC(
        history: ArrayDeque<ProcessedReading>,
        newValue: Double,
        newTimestamp: Long
    ): Double {
        if (history.size < 1) return 0.0

        // 使用最近最多6个点做线性回归
        val points = history.takeLast(5).toMutableList()
        points.add(ProcessedReading(newTimestamp, newValue, newValue, newValue, 0.0, 0, 0.0, 0, TrendCalculator.Trend.STABLE, false, false, ""))

        if (points.size < 2) return 0.0

        val n = points.size
        val startTime = points.first().timestamp
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumX2 = 0.0

        for (p in points) {
            val x = (p.timestamp - startTime) / 60000.0  // 分钟
            val y = p.filteredValue
            sumX += x; sumY += y; sumXY += x * y; sumX2 += x * x
        }

        val denom = n * sumX2 - sumX * sumX
        return if (abs(denom) > 1e-10) (n * sumXY - sumX * sumY) / denom else 0.0
    }

    /** 趋势分类 — 结合ROC和噪声等级 */
    private fun classifyTrend(roc: Double, noiseLevel: Int): TrendCalculator.Trend {
        // 高噪声时趋势不可信 → 显示平稳
        if (noiseLevel >= NOISE_HIGH) return TrendCalculator.Trend.STABLE

        return when {
            roc >= 0.17 -> TrendCalculator.Trend.RAPID_RISING
            roc >= 0.056 -> TrendCalculator.Trend.RISING
            roc >= -0.056 -> TrendCalculator.Trend.STABLE
            roc >= -0.17 -> TrendCalculator.Trend.FALLING
            else -> TrendCalculator.Trend.RAPID_FALLING
        }
    }

    /**
     * 质量评分 0-100
     *
     * 扣分项:
     *   - 噪声: NOISE_HIGH -30, NOISE_MODERATE -15, NOISE_LIGHT -5
     *   - 陈旧: -30
     *   - 间隙: -10
     *   - ROC异常(>1.0 mmol/L/min): -20
     */
    private fun calculateQualityScore(noiseLevel: Int, isStale: Boolean, isGap: Boolean, roc: Double): Int {
        var score = 100

        when (noiseLevel) {
            NOISE_HIGH -> score -= 30
            NOISE_MODERATE -> score -= 15
            NOISE_LIGHT -> score -= 5
            NOISE_EXTREME -> score -= 50
        }

        if (isStale) score -= 30
        if (isGap) score -= 10
        if (abs(roc) > 1.0) score -= 20  // >1 mmol/L/min 极不可能

        return score.coerceIn(0, 100)
    }

    /** 检查数据是否陈旧 */
    fun isDataStale(): Boolean {
        val last = recentReadings.lastOrNull() ?: return true
        return System.currentTimeMillis() - last.timestamp > STALE_THRESHOLD_MS
    }

    /** 获取最新处理后的读数 */
    fun getLatest(): ProcessedReading? = recentReadings.lastOrNull()

    /** 获取最近N个读数 */
    fun getRecent(n: Int = 30): List<ProcessedReading> = recentReadings.takeLast(n)

    /** 获取噪声统计 */
    fun getNoiseStats(): Map<String, Any> {
        if (recentReadings.isEmpty()) return mapOf("avg" to 0.0, "max" to 0, "clean%" to 0.0)
        val noises = recentReadings.map { it.noiseLevel }
        return mapOf(
            "avg" to noises.average(),
            "max" to (noises.maxOrNull() ?: 0),
            "clean%" to noises.count { it == NOISE_CLEAN }.toDouble() / noises.size * 100,
            "samples" to noises.size
        )
    }

    /** 重置监测器 */
    fun reset() {
        recentReadings.clear()
        _monitorState.value = MonitorState()
    }
}
