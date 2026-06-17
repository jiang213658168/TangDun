package com.tangdun.app.domain.algorithm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.*

/**
 * 预测误差驱动在线纠正 (Error-Driven Online Correction, EDOC)
 *
 * 核心理念：像人改错题一样学习
 *   每次CGM读数到达 → 查出5/30/60分钟前的预测 → 对比实际 → 立即微调参数
 *
 * 算法来源：
 *   - RLS (递归最小二乘): Bhattacharjee et al. 2019, MBEC
 *   - signSGD: 噪声环境下更鲁棒的梯度更新
 *   - NLMS: 输入功率自适应的学习率
 *   - 多时域分层修正: 5min→即时响应 / 30min→动态参数 / 60min→基线参数
 *
 * 安全机制：
 *   - 噪声门槛: |e| < sensor_noise → 跳过
 *   - 异常检测: |e| > 8 mmol/L → 跳过
 *   - 参数限幅: 每次最多变±0.5%, 每天最多变±10%
 *   - 方向感知: 连续同向加速修正, 频繁翻转减速
 *   - 生理边界: 所有参数约束在合理范围
 */
class EDOCCorrector(private val context: Context) {

    companion object {
        private const val TAG = "EDOC"
        private const val PREFS_NAME = "edoc_state"

        // 学习率基值 (三个时域)
        private const val ETA_5MIN_BASE  = 0.0003
        private const val ETA_30MIN_BASE = 0.001
        private const val ETA_60MIN_BASE = 0.003

        // 参数边界
        private const val MAX_STEP_RATIO  = 0.005   // 单次最多变0.5%
        private const val MAX_DAILY_RATIO = 0.10    // 一天最多变10%

        // 误差门槛
        private const val NOISE_FLOOR    = 0.3    // mmol/L, 最小噪声
        private const val MAX_ERROR      = 8.0    // mmol/L, 异常大误差
        private const val SENSOR_MARD    = 0.09   // CGM传感器典型MARD

        // 方向感知
        private const val DIRECTION_WINDOW = 8
        private const val ACCELERATE_STREAK = 4  // 连续4次同向→加速
        private const val DECELERATE_FLIPS  = 6  // 8次中6次翻转→减速

        // 误差窗口
        private const val ERROR_WINDOW = 36      // 3小时@5min步长
        private const val ACF1_THRESHOLD = 0.3   // 自相关低于此→白噪声,不修正

        // RLS
        private const val LAMBDA_INITIAL = 0.97   // 初始遗忘因子
        private const val LAMBDA_DAYS = 14        // 多少天后λ升到最终值
        private const val LAMBDA_FINAL = 0.99     // 最终遗忘因子

        // 参数编号
        const val IDX_KSTOMACH    = 0
        const val IDX_VMAXGASTRIC = 1
        const val IDX_VM0         = 2
        const val IDX_VMX         = 3
        const val IDX_HEPATIC     = 4
        const val IDX_KP3         = 5
        const val PARAM_COUNT     = 6
    }

    // ──── 参数修正偏移量 (加在DallaMan基础参数上) ────
    data class ParamDeltas(
        var kStomach: Double = 0.0,
        var vmaxGastric: Double = 0.0,
        var vm0: Double = 0.0,
        var vmX: Double = 0.0,
        var hepaticBase: Double = 0.0,
        var kp3: Double = 0.0
    ) {
        fun toMap(): Map<String, Double> = mapOf(
            "kStomach" to kStomach, "vmaxGastric" to vmaxGastric,
            "vm0" to vm0, "vmX" to vmX,
            "hepaticBase" to hepaticBase, "kp3" to kp3
        )

        fun get(idx: Int): Double = when (idx) {
            IDX_KSTOMACH    -> kStomach
            IDX_VMAXGASTRIC -> vmaxGastric
            IDX_VM0         -> vm0
            IDX_VMX         -> vmX
            IDX_HEPATIC     -> hepaticBase
            IDX_KP3         -> kp3
            else -> 0.0
        }

        fun set(idx: Int, value: Double) {
            when (idx) {
                IDX_KSTOMACH    -> kStomach = value
                IDX_VMAXGASTRIC -> vmaxGastric = value
                IDX_VM0         -> vm0 = value
                IDX_VMX         -> vmX = value
                IDX_HEPATIC     -> hepaticBase = value
                IDX_KP3         -> kp3 = value
            }
        }

        fun add(idx: Int, delta: Double) { set(idx, get(idx) + delta) }

        fun isEmpty(): Boolean = abs(kStomach) + abs(vmaxGastric) + abs(vm0) +
                abs(vmX) + abs(hepaticBase) + abs(kp3) < 1e-10
    }

    // ──── 缓存的预测 ────
    data class CachedPrediction(
        val madeAt: Long,            // 预测时刻 (ms)
        val glucose: Float,          // 当时的血糖值
        val curve: FloatArray        // 36步预测曲线
    ) {
        /** 取第step步的预测值 (0=当前, 1=5min, 6=30min, 12=60min) */
        fun atStep(step: Int): Double {
            return if (step in curve.indices) curve[step].toDouble() else curve.last().toDouble()
        }
    }

    /**
     * 单次修正动作记录
     */
    data class CorrectionAction(
        val timestamp: Long,
        val error: Double,            // 预测误差 (mmol/L)
        val quality: Double,          // 数据质量 (0-1)
        val errorType: String,        // "系统偏差"/"事件响应"/"混合误差"/"白噪声"
        val paramDeltas: Map<String, Double>,  // 各参数变化量
        val timeHorizon: String       // "5min"/"30min"/"60min"
    )

    /**
     * EDOC对外暴露的状态
     */
    data class Status(
        val isActive: Boolean,
        val totalCorrections: Int,
        val correctionsToday: Int,
        val lastAction: CorrectionAction?,
        val errorTrend: String,       // "改善中 ↓" / "稳定 →" / "退化 ↑"
        val adjustmentRate: String,   // "快速修正" / "正常" / "谨慎" / "待命中"
        val recentMAE: Double,        // 最近1h预测MAE
        val paramDrifts: Map<String, Double>,  // 参数名→累计变化%
        val learningRates: Map<String, Double> // 三个时域当前学习率
    )

    // ──── 状态变量 ────
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 参数修正偏移
    val deltas = ParamDeltas()

    // 预测缓存: madeAt → CachedPrediction, 最多保留300条
    private val predictionCache = object : LinkedHashMap<Long, CachedPrediction>(300, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedPrediction>): Boolean {
            return size > 300
        }
    }

    // 误差历史 (最近36个有效误差, 用于ACF计算)
    private val errorHistory = mutableListOf<Double>()

    // 方向追踪器 (记录最近8次修正的符号)
    private val directionTracker = mutableListOf<Int>()

    // RLS协方差矩阵 (6×6, 对角初始化)
    private val P = Array(PARAM_COUNT) { DoubleArray(PARAM_COUNT) }

    // 自适应学习率
    private var eta5min  = ETA_5MIN_BASE
    private var eta30min = ETA_30MIN_BASE
    private var eta60min = ETA_60MIN_BASE

    // 每日累计参数变化量 (绝对值之和, 用于每日上限)
    private val dailyChanges = DoubleArray(PARAM_COUNT)
    private var dailyResetDay = 0

    // 统计
    private var totalCorrections = 0
    private var correctionsToday = 0
    private var lastAction: CorrectionAction? = null

    // TCN adapter (128维特征→4维输出修正, 预留)
    // TODO: 接入TCN推理中间层的feature vector后启用LMS更新
    // private val tcnAdapter = FloatArray(4 * 128)

    init {
        initRLSMatrix()    // 先设默认值
        loadState()        // 再覆盖为持久化值 (P对角线等)
        Log.i(TAG, "EDOC初始化: 总修正${totalCorrections}次, η5min=$eta5min")
    }

    // ═══════════════════════════════════════════
    // 公共API
    // ═══════════════════════════════════════════

    /** 存储一次预测, 供后续误差计算使用 */
    fun storePrediction(glucose: Float, curve: FloatArray) {
        val now = System.currentTimeMillis()
        synchronized(predictionCache) {
            predictionCache[now] = CachedPrediction(
                madeAt = now,
                glucose = glucose,
                curve = curve.clone()
            )
        }
    }

    /**
     * 新CGM读数到达 → 查历史预测 → 算误差 → 修正参数
     *
     * @param currentGlucose 当前血糖 (mmol/L)
     * @param qualityScore   RealTimeGlucoseMonitor的质量评分 (0-1)
     * @param baseParams     当前DallaMan基础参数 (不含EDOC修正)
     * @return 修正动作 (null表示本次未触发修正)
     */
    fun onNewReading(
        currentGlucose: Double,
        qualityScore: Double,
        baseParams: DallaManModel.Parameters
    ): CorrectionAction? {
        val now = System.currentTimeMillis()

        // 重置每日计数器
        val todayDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (todayDay != dailyResetDay) {
            dailyChanges.fill(0.0)
            correctionsToday = 0
            dailyResetDay = todayDay
        }

        // 检查三个时域的预测缓存
        val results = mutableListOf<CorrectionAction?>()
        results.add(checkAndCorrect(now - 5 * 60_000,  currentGlucose, qualityScore, baseParams, "5min",  1))
        results.add(checkAndCorrect(now - 30 * 60_000, currentGlucose, qualityScore, baseParams, "30min", 6))
        results.add(checkAndCorrect(now - 60 * 60_000, currentGlucose, qualityScore, baseParams, "60min", 12))

        // 返回最近一次有效修正
        val validResults = results.filterNotNull()
        if (validResults.isNotEmpty()) {
            lastAction = validResults.last()
            persistState()
        }
        return validResults.lastOrNull()
    }

    /**
     * 批量导入数据 → 离线模拟EDOC
     *
     * 对导入的历史数据, 按时间顺序逐条处理:
     *   用当前模型+前一条数据的状态做预测, 和下一条实际值对比, 累计修正
     *
     * @param historyRecords 按时间升序排列的血糖记录
     * @param baseParams 当前DallaMan基础参数
     * @param onProgress 进度回调 (已处理条数, 总条数, 修正次数)
     */
    fun processBatchImport(
        historyRecords: List<com.tangdun.app.data.local.entity.GlucoseRecord>,
        baseParams: DallaManModel.Parameters,
        onProgress: (Int, Int, Int) -> Unit
    ): Int {
        if (historyRecords.size < 2) return 0

        var batchCorrections = 0
        val saved5min = eta5min
        val saved30min = eta30min
        eta5min  *= 2.0
        eta30min *= 2.0

        try {
            for (i in 1 until historyRecords.size) {
                val prev = historyRecords[i - 1]
                val curr = historyRecords[i]
                val dt = (curr.timestamp - prev.timestamp) / 60000.0  // 分钟

                // 只在时间间隔合理时处理 (4-65分钟)
                if (dt !in 4.0..65.0) continue

                // ★ 批量导入无缓存预测 → 用单步模型+当前参数生成"伪预测"
                val effectiveParams = applyDeltas(baseParams)
                val syntheticPred = runOneStepRK4(effectiveParams, prev.value)
                val error = curr.value - syntheticPred

                val quality = 0.7
                val label = when { dt <= 10.0 -> "5min"; dt <= 35.0 -> "30min"; else -> "60min" }

                val result = applyCorrection(error, quality, baseParams, label)
                if (result != null) batchCorrections++

                if (i % 50 == 0 || i == historyRecords.size - 1) {
                    onProgress(i + 1, historyRecords.size, batchCorrections)
                }
            }
        } finally {
            eta5min = saved5min
            eta30min = saved30min
        }

        persistState()
        Log.i(TAG, "批量导入完成: ${historyRecords.size}条 → $batchCorrections 次修正")
        return batchCorrections
    }

    /** 获取当前状态 (供UI展示) */
    fun getStatus(recentErrors: List<Double> = emptyList()): Status {
        val recentMAE = if (recentErrors.isNotEmpty()) recentErrors.map { abs(it) }.average() else 0.0

        // 误差趋势
        val errorTrend = when {
            recentErrors.size < 6 -> "收集数据中…"
            else -> {
                val firstHalf = recentErrors.take(recentErrors.size / 2).map { abs(it) }.average()
                val secondHalf = recentErrors.drop(recentErrors.size / 2).map { abs(it) }.average()
                when {
                    secondHalf < firstHalf * 0.8 -> "改善中 ↓"
                    secondHalf > firstHalf * 1.2 -> "退化 ↑"
                    else -> "稳定 →"
                }
            }
        }

        // 调整速率: 基于修正频率和方向稳定性
        val adjustmentRate = when {
            totalCorrections == 0 -> "待命中"
            totalCorrections < 10 -> "快速修正"  // 新用户/刚启动
            else -> {
                val sameStreak = maxConsecutiveSameSign(directionTracker)
                val flips = directionTracker.windowed(2).count { (a, b) -> a != b }
                when {
                    sameStreak >= 5 -> "快速修正"   // 持续同向→系统偏差→加速
                    flips >= 5 -> "谨慎"            // 频繁翻转→噪声→减速
                    else -> "正常"
                }
            }
        }

        // 参数漂移 (相对于典型值的百分比)
        val paramDrifts = mutableMapOf<String, Double>()
        val typicals = doubleArrayOf(0.045, 8.0, 4.0, 0.13, 2.4, 0.038)
        val names = arrayOf("kStomach", "VmaxGastric", "Vm0", "VmX", "hepaticBase", "kp3")
        for (i in 0 until PARAM_COUNT) {
            if (typicals[i] > 0) {
                paramDrifts[names[i]] = (deltas.get(i) / typicals[i] * 100)
            }
        }

        return Status(
            isActive = totalCorrections > 0,
            totalCorrections = totalCorrections,
            correctionsToday = correctionsToday,
            lastAction = lastAction,
            errorTrend = errorTrend,
            adjustmentRate = adjustmentRate,
            recentMAE = recentMAE,
            paramDrifts = paramDrifts,
            learningRates = mapOf(
                "5min" to eta5min,
                "30min" to eta30min,
                "60min" to eta60min
            )
        )
    }

    /**
     * 清除EDOC学习状态 (用户重置)
     */
    fun reset() {
        synchronized(predictionCache) { predictionCache.clear() }
        errorHistory.clear()
        directionTracker.clear()
        initRLSMatrix()
        eta5min = ETA_5MIN_BASE
        eta30min = ETA_30MIN_BASE
        eta60min = ETA_60MIN_BASE
        dailyChanges.fill(0.0)
        totalCorrections = 0
        correctionsToday = 0
        lastAction = null
        deltas.kStomach = 0.0; deltas.vmaxGastric = 0.0; deltas.vm0 = 0.0
        deltas.vmX = 0.0; deltas.hepaticBase = 0.0; deltas.kp3 = 0.0
        persistState()
        Log.i(TAG, "EDOC已重置")
    }

    // ═══════════════════════════════════════════
    // 核心算法
    // ═══════════════════════════════════════════

    /**
     * 对单个时域做误差检查和参数修正 (实时模式：查预测缓存)
     */
    private fun checkAndCorrect(
        predictTime: Long, actualValue: Double, quality: Double,
        baseParams: DallaManModel.Parameters, label: String, step: Int
    ): CorrectionAction? {
        // 查缓存
        val cached = synchronized(predictionCache) { findNearestPrediction(predictTime) }
            ?: return null
        val predicted = cached.atStep(step)
        val error = actualValue - predicted
        return applyCorrection(error, quality, baseParams, label)
    }

    /**
     * 应用修正 (核心逻辑, 被实时模式和批量模式共用)
     *
     * @param error 预测误差 (实际 - 预测, mmol/L)
     * @param quality 数据质量 (0-1)
     * @param baseParams 当前DallaMan基础参数
     * @param label 时域标签
     */
    private fun applyCorrection(
        error: Double, quality: Double,
        baseParams: DallaManModel.Parameters, label: String
    ): CorrectionAction? {
        val actualValue = abs(error)  // 用于噪声门槛计算 (近似)

        // ── 噪声过滤 ──
        val noiseStd = max(SENSOR_MARD * max(actualValue, 5.0) / 100.0, NOISE_FLOOR)
        val effectiveThreshold = noiseStd * (2.0 - quality)
        if (abs(error) < effectiveThreshold) return null

        // 异常检测
        if (abs(error) > MAX_ERROR) {
            Log.w(TAG, "异常大误差 ${String.format("%.1f", error)} mmol/L, 跳过")
            return null
        }

        // ── 误差分类 ──
        errorHistory.add(error)
        while (errorHistory.size > ERROR_WINDOW) errorHistory.removeAt(0)

        val errorType = classifyError(errorHistory)
        if (errorType == "白噪声") return null

        // ── 状态感知梯度 (用合理的当前血糖值) ──
        val gradValues = computeSensitivities(baseParams)

        // ── 确定修正参数: 时域+误差类型联合决策 ──
        val paramIndices = when (label) {
            "5min"  -> intArrayOf(IDX_KSTOMACH)
            "30min" -> intArrayOf(IDX_KSTOMACH, IDX_VM0, IDX_VMX, IDX_KP3)
            "60min" -> intArrayOf(IDX_HEPATIC)
            else    -> intArrayOf(IDX_KSTOMACH, IDX_VM0, IDX_VMX, IDX_HEPATIC, IDX_KP3)
        }

        // 误差类型→参数选择: 系统偏差只调基线, 混合误差全调
        // TODO: 接入meal事件数据后可区分"事件响应错误"→调kStomach/Vm/VmX
        val effectiveIndices = when (errorType) {
            "系统偏差" -> intArrayOf(IDX_HEPATIC)
            else -> paramIndices  // 混合误差→按原时域策略全调
        }

        // ── 学习率 ──
        val baseEta = when (label) {
            "5min"  -> eta5min; "30min" -> eta30min; "60min" -> eta60min; else -> eta30min
        }

        // ── Sign-based参数更新 ──
        val paramDeltas = mutableMapOf<String, Double>()
        val totalGrad = gradValues.map { abs(it) }.sum().coerceAtLeast(1e-6)

        for (idx in effectiveIndices) {
            val grad = gradValues[idx]
            // ★ 每个参数用自己的RLS对角线
            val rlsDiag = P[idx][idx].coerceIn(0.1, 10.0)
            val attribution = abs(grad) / totalGrad

            // Sign-SGD: delta = η × error × sign(grad) × attribution / rlsDiag
            val delta = baseEta * error * sign(grad) * attribution / rlsDiag

            // 参数限幅
            val paramBase = getBaseParam(baseParams, idx)
            val maxStepDelta = abs(paramBase) * MAX_STEP_RATIO
            val clampedDelta = delta.coerceIn(-maxStepDelta, maxStepDelta)

            // 每日上限
            val remainingDailyBudget = abs(paramBase) * MAX_DAILY_RATIO - dailyChanges[idx]
            val finalDelta = when {
                abs(clampedDelta) > remainingDailyBudget && remainingDailyBudget > 0 ->
                    sign(clampedDelta) * remainingDailyBudget
                remainingDailyBudget <= 0 -> 0.0
                else -> clampedDelta
            }

            deltas.add(idx, finalDelta)
            dailyChanges[idx] += abs(finalDelta)
            paramDeltas[paramName(idx)] = finalDelta
        }

        // ── 方向追踪 + 学习率自适应 ──
        directionTracker.add(sign(error).toInt())
        while (directionTracker.size > DIRECTION_WINDOW) directionTracker.removeAt(0)
        adjustLearningRates(label)

        // ── RLS协方差更新 ──
        updateRLSMatrix(gradValues)

        totalCorrections++
        correctionsToday++

        val action = CorrectionAction(
            timestamp = System.currentTimeMillis(),
            error = error, quality = quality,
            errorType = errorType, paramDeltas = paramDeltas, timeHorizon = label
        )

        Log.d(TAG, "[$label] e=${String.format("%.1f", error)} $errorType → " +
                paramDeltas.entries.joinToString { "${it.key}=${String.format("%+.4f", it.value)}" })

        return action
    }

    // ═══════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════

    /** 在缓存中找到最匹配的预测 (±30秒容差) */
    private fun findNearestPrediction(targetTime: Long): CachedPrediction? {
        return predictionCache[targetTime]
            ?: predictionCache.entries
                .minByOrNull { abs(it.key - targetTime) }
                ?.takeIf { abs(it.key - targetTime) < 30_000 }  // 30秒容差
                ?.value
    }

    /**
     * 误差分类: 基于最近误差序列的自相关
     *
     * ACF(1) > 0.7  → 系统偏差 (误差有强持续性, 模型有固定方向偏差→调基线)
     * ACF(1) < 0.3  → 白噪声 (误差随机, 不修正)
     * 否则          → 混合误差 (有模式但不明显, 正常修正)
     */
    private fun classifyError(errors: List<Double>): String {
        if (errors.size < 6) return "混合误差"

        // 计算一阶自相关
        val n = errors.size
        val mean = errors.average()
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until n) {
            val dev = errors[i] - mean
            denominator += dev * dev
            if (i < n - 1) numerator += dev * (errors[i + 1] - mean)
        }
        if (denominator < 1e-6) return "白噪声"
        val acf1 = numerator / denominator

        return when {
            acf1 > 0.7 -> "系统偏差"
            acf1 < ACF1_THRESHOLD -> "白噪声"
            else -> "混合误差"
        }
    }

    /**
     * 有限差分灵敏度分析
     *
     * 对6个DallaMan参数各自微扰±1%, 用一步模型算预测值变化
     * 只关心梯度方向(sign), 不关心精确幅度 → 一步Euler足够
     */
    private fun computeSensitivities(baseParams: DallaManModel.Parameters): DoubleArray {
        val sensitivities = DoubleArray(PARAM_COUNT)
        val eps = 0.01  // 1%微扰
        val g = 7.0     // 典型血糖值(mmol/L), 用于灵敏度计算

        for (i in 0 until PARAM_COUNT) {
            val paramVal = getBaseParam(baseParams, i) + deltas.get(i)
            val absEps = max(abs(paramVal) * eps, 1e-6)

            // 正微扰: base参数×(1+eps) + 其他参数的deltas
            val basePos = setBaseParamOnly(baseParams, i, getBaseParam(baseParams, i) * (1.0 + eps))
            val effPos = applyDeltas(basePos)
            val predPos = runOneStepRK4(effPos, g)

            // 负微扰
            val baseNeg = setBaseParamOnly(baseParams, i, getBaseParam(baseParams, i) * (1.0 - eps))
            val effNeg = applyDeltas(baseNeg)
            val predNeg = runOneStepRK4(effNeg, g)

            // 中心差分
            sensitivities[i] = (predPos - predNeg) / (2.0 * absEps)
        }

        return sensitivities
    }

    /**
     * 一步预测 (5分钟, Euler近似)
     *
     * 简化版Dalla Man血糖ODE, 只用于灵敏度方向计算
     * params已包含EDOC修正(调用方先applyDeltas), 这里直接使用
     *
     * 注意: 用假定的胃内容物(30g碳水等效)使kStomach/VmaxGastric有非零灵敏度
     *       灵敏度只关心符号方向, 不关心绝对幅度
     */
    private fun runOneStepRK4(params: DallaManModel.Parameters, glucose: Double): Double {
        val dt = 5.0
        val g = glucose.coerceIn(2.0, 35.0)
        val Vg = params.Vg
        val BW = params.bodyWeight

        // Ra (葡萄糖吸收): 假设典型胃内容物≈30g碳水 → kStomach/VmaxGastric有非零灵敏度
        val assumedStomach = 30_000.0  // mg (≈30g碳水)
        val gastricEmptying = min(params.kStomach * assumedStomach, params.VmaxGastric * BW)
        val ra = gastricEmptying * params.fCarbs / BW  // mg/kg/min

        // Uii (胰岛素非依赖利用)
        val uii = params.k1 * Vg * BW

        // Uid (MM动力学, X≈0)
        val G_mgdl = g * 18.0
        val uid = params.Vm0 * G_mgdl / (params.Km0 + G_mgdl) * BW / (Vg * 18.0)

        // EGP (肝糖输出)
        val egp = params.hepaticBase * BW

        // Euler步 (dt短, 近似足够)
        val dg_dt = (ra + egp - uii - uid) / (Vg * BW)
        return (g + dg_dt * dt).coerceIn(2.0, 35.0)
    }

    /** 自适应学习率: 同向加速, 翻转减速 */
    private fun adjustLearningRates(label: String) {
        if (directionTracker.size < DIRECTION_WINDOW / 2) return

        val sameStreak = maxConsecutiveSameSign(directionTracker)
        val flips = directionTracker.windowed(2).count { (a, b) -> a != b }

        val multiplier = when {
            sameStreak >= ACCELERATE_STREAK -> 1.02   // 缓慢加速
            flips >= DECELERATE_FLIPS -> 0.95          // 快速减速
            else -> 1.0
        }

        when (label) {
            "5min"  -> eta5min  = (eta5min  * multiplier).coerceIn(ETA_5MIN_BASE * 0.3,  ETA_5MIN_BASE * 6.0)
            "30min" -> eta30min = (eta30min * multiplier).coerceIn(ETA_30MIN_BASE * 0.3, ETA_30MIN_BASE * 6.0)
            "60min" -> eta60min = (eta60min * multiplier).coerceIn(ETA_60MIN_BASE * 0.3, ETA_60MIN_BASE * 6.0)
        }
    }

    /** RLS协方差矩阵更新 (6×6) */
    private fun updateRLSMatrix(gradients: DoubleArray) {
        val lambda = computeLambda()
        // 简化RLS: 只更新对角线 (足够追踪各参数的不确定性)
        for (i in 0 until PARAM_COUNT) {
            val g = gradients[i]
            val p_ii = P[i][i]
            // P_new = P_old / (λ + g²·P_old)  (单变量RLS公式)
            P[i][i] = p_ii / (lambda + g * g * p_ii)
            // 防止P变得太小 (太小→不学习) 或太大 (太大→不稳定)
            P[i][i] = P[i][i].coerceIn(0.01, 100.0)
        }
    }

    /** 自适应遗忘因子: 新用户λ低(快学), 老用户λ高(稳学) */
    private fun computeLambda(): Double {
        val onlineParams = SelfLearningManager.getOnlineLearner().getPersonalParams()
        val dataDays = onlineParams.dataDays
        val progress = (dataDays / LAMBDA_DAYS).coerceIn(0.0, 1.0)
        return LAMBDA_INITIAL + (LAMBDA_FINAL - LAMBDA_INITIAL) * progress
    }

    /** 连续同号的最大长度 */
    private fun maxConsecutiveSameSign(seq: List<Int>): Int {
        if (seq.isEmpty()) return 0
        var maxStreak = 1; var cur = 1
        for (i in 1 until seq.size) {
            if (seq[i] == seq[i - 1] && seq[i] != 0) { cur++; maxStreak = max(maxStreak, cur) }
            else { cur = 1 }
        }
        return maxStreak
    }

    /** 应用EDOC修正到DallaMan参数 */
    fun applyDeltas(base: DallaManModel.Parameters): DallaManModel.Parameters {
        if (deltas.isEmpty()) return base
        return base.copy(
            kStomach    = (base.kStomach    + deltas.kStomach   ).coerceIn(0.020, 0.080),
            VmaxGastric = (base.VmaxGastric + deltas.vmaxGastric).coerceIn(3.0, 15.0),
            Vm0         = (base.Vm0         + deltas.vm0        ).coerceIn(1.5, 8.0),
            VmX         = (base.VmX         + deltas.vmX        ).coerceIn(0.03, 0.25),
            hepaticBase = (base.hepaticBase + deltas.hepaticBase).coerceIn(0.5, 5.0),
            kp3         = (base.kp3         + deltas.kp3        ).coerceIn(0.015, 0.070)
        )
    }

    // ──── 参数访问辅助 ────
    private fun getBaseParam(params: DallaManModel.Parameters, idx: Int): Double = when (idx) {
        IDX_KSTOMACH    -> params.kStomach
        IDX_VMAXGASTRIC -> params.VmaxGastric
        IDX_VM0         -> params.Vm0
        IDX_VMX         -> params.VmX
        IDX_HEPATIC     -> params.hepaticBase
        IDX_KP3         -> params.kp3
        else -> 0.0
    }

    /**
     * 只修改base参数 (不改delta)
     * 用于灵敏度分析: 微扰base后通过applyDeltas合成effective参数
     */
    private fun setBaseParamOnly(params: DallaManModel.Parameters, idx: Int, value: Double): DallaManModel.Parameters =
        when (idx) {
            IDX_KSTOMACH    -> params.copy(kStomach = value)
            IDX_VMAXGASTRIC -> params.copy(VmaxGastric = value)
            IDX_VM0         -> params.copy(Vm0 = value)
            IDX_VMX         -> params.copy(VmX = value)
            IDX_HEPATIC     -> params.copy(hepaticBase = value)
            IDX_KP3         -> params.copy(kp3 = value)
            else -> params
        }

    private fun paramName(idx: Int): String = when (idx) {
        IDX_KSTOMACH    -> "kStomach"
        IDX_VMAXGASTRIC -> "VmaxGastric"
        IDX_VM0         -> "Vm0"
        IDX_VMX         -> "VmX"
        IDX_HEPATIC     -> "hepaticBase"
        IDX_KP3         -> "kp3"
        else -> "unknown"
    }

    // ──── 持久化 ────
    private fun initRLSMatrix() {
        for (i in 0 until PARAM_COUNT) {
            P[i].fill(0.0)
            P[i][i] = 50.0  // 初始高不确定性 (新用户)
        }
    }

    private fun persistState() {
        prefs.edit().apply {
            putInt("total_corrections", totalCorrections)
            putInt("corrections_today", correctionsToday)
            putFloat("eta_5min", eta5min.toFloat())
            putFloat("eta_30min", eta30min.toFloat())
            putFloat("eta_60min", eta60min.toFloat())
            // 参数偏移
            putFloat("delta_kstomach", deltas.kStomach.toFloat())
            putFloat("delta_vmaxgastric", deltas.vmaxGastric.toFloat())
            putFloat("delta_vm0", deltas.vm0.toFloat())
            putFloat("delta_vmx", deltas.vmX.toFloat())
            putFloat("delta_hepatic", deltas.hepaticBase.toFloat())
            putFloat("delta_kp3", deltas.kp3.toFloat())
            // RLS对角线
            for (i in 0 until PARAM_COUNT) {
                putFloat("P_$i", P[i][i].toFloat())
            }
            apply()
        }
    }

    private fun loadState() {
        totalCorrections = prefs.getInt("total_corrections", 0)
        correctionsToday = prefs.getInt("corrections_today", 0)
        eta5min  = prefs.getFloat("eta_5min", ETA_5MIN_BASE.toFloat()).toDouble()
        eta30min = prefs.getFloat("eta_30min", ETA_30MIN_BASE.toFloat()).toDouble()
        eta60min = prefs.getFloat("eta_60min", ETA_60MIN_BASE.toFloat()).toDouble()
        deltas.kStomach    = prefs.getFloat("delta_kstomach", 0f).toDouble()
        deltas.vmaxGastric = prefs.getFloat("delta_vmaxgastric", 0f).toDouble()
        deltas.vm0         = prefs.getFloat("delta_vm0", 0f).toDouble()
        deltas.vmX         = prefs.getFloat("delta_vmx", 0f).toDouble()
        deltas.hepaticBase = prefs.getFloat("delta_hepatic", 0f).toDouble()
        deltas.kp3         = prefs.getFloat("delta_kp3", 0f).toDouble()
        for (i in 0 until PARAM_COUNT) {
            P[i][i] = prefs.getFloat("P_$i", 50f).toDouble()
        }
    }
}
