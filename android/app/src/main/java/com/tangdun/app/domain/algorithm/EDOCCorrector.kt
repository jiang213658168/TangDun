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

    // TCN adapter (128维特征→4维输出修正)
    private val tcnAdapter = FloatArray(4 * 128)  // 4×128 修正矩阵

    init {
        loadState()
        initRLSMatrix()
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
        // 批量导入时使用更高的学习率 (少见数据, 信息量大)
        val saved5min = eta5min
        val saved30min = eta30min
        eta5min  *= 2.0
        eta30min *= 2.0

        try {
            for (i in 1 until historyRecords.size) {
                val prev = historyRecords[i - 1]
                val curr = historyRecords[i]
                val dt = (curr.timestamp - prev.timestamp) / 60000.0  // 分钟

                // 只在时间间隔合理时处理 (5-65分钟)
                if (dt !in 4.0..65.0) continue

                val step = (dt / 5.0).roundToInt().coerceIn(1, 13)
                val quality = 0.7  // 导入数据默认质量 (无实时噪声信息)

                val result = checkAndCorrect(curr.timestamp, curr.value, quality, baseParams,
                    when { step <= 2 -> "5min"; step <= 7 -> "30min"; else -> "60min" }, step)

                if (result != null) batchCorrections++

                // 进度回调 (每50条或最后一条)
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

        // 调整速率
        val adjustmentRate = when {
            totalCorrections < 10 -> "快速修正"
            directionTracker.count { it > 0 } in 4..6 -> "正常"
            directionTracker.count { it > 0 } >= 6 -> "谨慎"
            else -> "待命中"
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
     * 对单个时域做误差检查和参数修正
     *
     * @param predictTime 预测发生的时间戳
     * @param actualValue 当前实际血糖值
     * @param quality 数据质量 (0-1)
     * @param baseParams 当前DallaMan基础参数
     * @param label 时域标签
     * @param step 预测曲线上第几步 (1=5min, 6=30min, 12=60min)
     */
    private fun checkAndCorrect(
        predictTime: Long,
        actualValue: Double,
        quality: Double,
        baseParams: DallaManModel.Parameters,
        label: String,
        step: Int
    ): CorrectionAction? {
        // 查缓存: 在predictTime时刻有没有做过预测
        val cached = synchronized(predictionCache) { findNearestPrediction(predictTime) }
        if (cached == null) return null

        val predicted = cached.atStep(step)
        val error = actualValue - predicted  // 正=低估, 负=高估

        // ── 噪声过滤 ──
        val noiseStd = max(SENSOR_MARD * actualValue / 100.0, NOISE_FLOOR)
        val effectiveThreshold = noiseStd * (2.0 - quality)
        if (abs(error) < effectiveThreshold) return null  // 在噪声范围内

        // 异常检测
        if (abs(error) > MAX_ERROR) {
            Log.w(TAG, "异常大误差 ${String.format("%.1f", error)} mmol/L, 跳过")
            return null
        }

        // ── 误差分类 ──
        errorHistory.add(error)
        while (errorHistory.size > ERROR_WINDOW) errorHistory.removeAt(0)

        val errorType = classifyError(errorHistory)
        if (errorType == "白噪声") {
            Log.d(TAG, "白噪声, 跳过修正")
            return null
        }

        // ── 状态感知梯度 ──
        val gradValues = computeSensitivities(baseParams, actualValue, quality)

        // ── 确定这个时域修正哪些参数 ──
        val paramIndices = when (label) {
            "5min"  -> intArrayOf(IDX_KSTOMACH)                           // 即时: 胃排空
            "30min" -> intArrayOf(IDX_KSTOMACH, IDX_VM0, IDX_VMX, IDX_KP3) // 短期: 动态参数
            "60min" -> intArrayOf(IDX_HEPATIC)                             // 基线: 肝糖输出
            else    -> intArrayOf(IDX_KSTOMACH, IDX_VM0, IDX_VMX, IDX_HEPATIC, IDX_KP3)
        }

        // ── 系统偏差→只调基线, 事件响应→只调事件参数 ──
        val effectiveIndices = when (errorType) {
            "系统偏差"   -> intArrayOf(IDX_HEPATIC)
            "事件响应错误" -> intArrayOf(IDX_KSTOMACH, IDX_VM0, IDX_VMX)
            else -> paramIndices  // 混合误差→全调
        }

        // ── 自适应学习率 ──
        val baseEta = when (label) {
            "5min"  -> eta5min
            "30min" -> eta30min
            "60min" -> eta60min
            else    -> eta30min
        }

        // RLS协方差缩放 (P对角线大=不确定性高=步长小)
        val rlsScale = 1.0 / (1.0 + P[0][0] * 0.001)

        // ── Sign-based参数更新 ──
        val paramDeltas = mutableMapOf<String, Double>()
        for (idx in effectiveIndices) {
            val grad = gradValues[idx]
            val rlsDiag = P[idx][idx].coerceIn(0.1, 10.0)
            val attribution = abs(grad) / (gradValues.map { abs(it) }.sum().coerceAtLeast(1e-6))

            // Sign-SGD: delta = η × error × sign(gradient) × attribution × RLS_scale
            val delta = baseEta * error * sign(grad) * attribution * rlsScale / (rlsDiag * 0.5)

            // ── 参数限幅 ──
            val paramBase = getBaseParam(baseParams, idx)
            val maxStepDelta = abs(paramBase) * MAX_STEP_RATIO
            val clampedDelta = delta.coerceIn(-maxStepDelta, maxStepDelta)

            // 每日上限
            val remainingDailyBudget = abs(paramBase) * MAX_DAILY_RATIO - dailyChanges[idx]
            val finalDelta = if (abs(clampedDelta) > remainingDailyBudget && remainingDailyBudget > 0) {
                sign(clampedDelta) * remainingDailyBudget
            } else if (remainingDailyBudget <= 0) {
                0.0
            } else {
                clampedDelta
            }

            // 应用修正
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
            error = error,
            quality = quality,
            errorType = errorType,
            paramDeltas = paramDeltas,
            timeHorizon = label
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
     * ACF(1) > 0.7  → 系统偏差 (误差有强持续性, 模型有固定方向偏差)
     * ACF(1) < 0.3  → 白噪声 (误差随机, 不修正)
     * 否则          → 混合误差
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
     * 对6个DallaMan参数各自微扰±1%, 看预测值变化多少
     * 用一步RK4 (5min) 而非完整36步, 因为计算量是关键
     */
    private fun computeSensitivities(
        baseParams: DallaManModel.Parameters,
        currentGlucose: Double,
        quality: Double
    ): DoubleArray {
        val sensitivities = DoubleArray(PARAM_COUNT)
        val eps = 0.01  // 1%微扰

        // 构建当前状态 (简化: 用基准参数+EDOC修正)
        val effectiveParams = applyDeltas(baseParams)

        // 基线预测 (t=0→5min, 一步RK4)
        val basePred = runOneStepRK4(effectiveParams, 0.0, currentGlucose)

        for (i in 0 until PARAM_COUNT) {
            val paramVal = getBaseParam(baseParams, i) + deltas.get(i)
            val absEps = max(abs(paramVal) * eps, 1e-6)

            // 正微扰
            val paramsPos = applyDeltas(baseParams).let { p ->
                setParam(p, i, getParam(p, i) * (1.0 + eps))
            }
            val predPos = runOneStepRK4(paramsPos, 0.0, currentGlucose)

            // 负微扰
            val paramsNeg = applyDeltas(baseParams).let { p ->
                setParam(p, i, getParam(p, i) * (1.0 - eps))
            }
            val predNeg = runOneStepRK4(paramsNeg, 0.0, currentGlucose)

            // 中心差分梯度
            sensitivities[i] = (predPos - predNeg) / (2.0 * absEps)
        }

        return sensitivities
    }

    /**
     * 一步RK4预测 (5分钟)
     *
     * 简化版Dalla Man ODE, 只跑一个dt=5min的RK4步
     * 返回5分钟后的血糖预测值
     */
    private fun runOneStepRK4(
        params: DallaManModel.Parameters,
        initialG: Double,
        currentGlucose: Double
    ): Double {
        val dt = 5.0  // 分钟
        var g = currentGlucose.coerceIn(2.0, 35.0)

        // 简化ODE (只计算血糖相关项, 假设其他状态初始为0)
        // dG/dt = (Ra + EGP - Uii - Uid) / (Vg × BW)
        // 简化: 只保留主导项来近似灵敏度方向
        val Vg = params.Vg
        val BW = params.bodyWeight

        // Uii (胰岛素非依赖利用)
        val uii = params.k1 * Vg * BW

        // Uid (MM动力学) - 假设X≈0 (无额外胰岛素作用)
        val G_mgdl = g * 18.0
        val vm = params.Vm0 + deltas.vm0
        val uid = vm * G_mgdl / (params.Km0 + G_mgdl) * BW / (Vg * 18.0)

        // EGP (肝糖输出)
        val egp = (params.hepaticBase + deltas.hepaticBase) * BW

        // 简化RK4 (Euler, 因为步长很短)
        val dg_dt = (egp - uii - uid) / (Vg * BW)
        g += dg_dt * dt

        return g.coerceIn(2.0, 35.0)
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

    private fun getParam(params: DallaManModel.Parameters, idx: Int): Double =
        getBaseParam(params, idx) + deltas.get(idx)

    private fun setParam(params: DallaManModel.Parameters, idx: Int, value: Double): DallaManModel.Parameters =
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
