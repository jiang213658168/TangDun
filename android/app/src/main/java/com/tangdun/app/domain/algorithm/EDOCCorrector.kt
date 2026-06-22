package com.tangdun.app.domain.algorithm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // ★ v3.0.13 修: NOISE_FLOOR 0.3 → 0.15 (CGM 真实 MARD 9% 在 8mmol 是 0.72, 阈值 0.39 太严,
        //   正常 0.3-0.5 mmol 误差全被拒 → totalCorrections 永远是 0)
        private const val NOISE_FLOOR    = 0.15   // mmol/L, 最小噪声
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
        val recentActions: List<CorrectionAction>,  // 最近5次修正
        val errorTrend: String,
        val adjustmentRate: String,
        val recentMAE: Double,
        val paramDrifts: Map<String, Double>,
        val learningRates: Map<String, Double>
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

    // ★ v3.0.13: 上次实际读数 — 用于没有预测缓存时线性外推生成伪预测
    //   即使用户从未打开过预测页面, EDOC 也能每条数据都纠一次
    @Volatile private var lastReadingValue: Double = Double.NaN
    @Volatile private var lastReadingTime: Long = 0L

    // ★ 线程安全锁: errorHistory和directionTracker同时在IO(写)和UI(读)访问
    private val trackerLock = Any()

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
    private val recentActions = mutableListOf<CorrectionAction>()  // 最近5次修正

    // TCN adapter (128维特征→4维输出修正, 预留)
    // TODO: 接入TCN推理中间层的feature vector后启用LMS更新
    // private val tcnAdapter = FloatArray(4 * 128)

    /**
     * EDOC上下文特征 — 让灵敏度分析知道患者在什么生理状态
     */
    data class SnapContext(
        val currentGlucose: Double,       // 当前血糖 (mmol/L)
        val glucoseROC: Double,           // 血糖变化率 (mmol/L/min, 正=上升)
        val recentCarbs: Double,          // 最近4h碳水总量 (g, 0=空腹)
        val minutesSinceMeal: Double,     // 距上次进食分钟数 (MAX_VALUE=无记录)
        val iob: Double,                  // 活性胰岛素 (U)
        val minutesSinceBolus: Double,    // 距上次bolus分钟数 (MAX_VALUE=无记录)
        val hourOfDay: Int                // 当前小时 (0-23, 用于昼夜节律)
    ) {
        val isFasting: Boolean get() = recentCarbs < 5.0 && minutesSinceMeal > 240.0
        val hasActiveInsulin: Boolean get() = iob > 0.1
        val isRising: Boolean get() = glucoseROC > 0.03
        val isFalling: Boolean get() = glucoseROC < -0.03
        val mealSize: String get() = when {
            recentCarbs > 50 -> "large"; recentCarbs > 20 -> "moderate"
            recentCarbs > 5 -> "small"; else -> "none"
        }
    }

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
        baseParams: DallaManModel.Parameters,
        context: SnapContext = SnapContext(currentGlucose, 0.0, 0.0, Double.MAX_VALUE, 0.0, Double.MAX_VALUE, 0)
    ): CorrectionAction? {
        val now = System.currentTimeMillis()

        // 重置每日计数器
        val todayDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (todayDay != dailyResetDay) {
            dailyChanges.fill(0.0)
            correctionsToday = 0
            dailyResetDay = todayDay
        }

        // 检查三个时域的预测缓存 — 每个时域单独查自己时段的预测 (容差 90 分钟)
        val results = mutableListOf<CorrectionAction?>()
        results.add(checkAndCorrect(now - 5 * 60_000,  currentGlucose, qualityScore, baseParams, context, "5min",  1))
        results.add(checkAndCorrect(now - 30 * 60_000, currentGlucose, qualityScore, baseParams, context, "30min", 6))
        results.add(checkAndCorrect(now - 60 * 60_000, currentGlucose, qualityScore, baseParams, context, "60min", 12))

        // 返回最近一次有效修正
        val validResults = results.filterNotNull()

        // ★ v3.0.13: 记录本次读数, 供下次 fallback 时线性外推用
        if (currentGlucose in 1.0..30.0) {
            lastReadingValue = currentGlucose
            lastReadingTime = now
        }

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
     * 修复前: 这是同步阻塞函数, 1000 条历史 → 27000 次 RK4 → UI 卡 30-60 秒
     * 修复后: 改为 suspend, 内部 withContext(Dispatchers.Default) 跑 CPU 密集循环,
     *         调用方在协程里 await, UI 不阻塞
     *
     * @param historyRecords 按时间升序排列的血糖记录
     * @param baseParams 当前DallaMan基础参数
     * @param onProgress 进度回调 (已处理条数, 总条数, 修正次数)
     */
    suspend fun processBatchImport(
        historyRecords: List<com.tangdun.app.data.local.entity.GlucoseRecord>,
        baseParams: DallaManModel.Parameters,
        onProgress: (Int, Int, Int) -> Unit
    ): Int = withContext(Dispatchers.Default) {
        if (historyRecords.size < 2) return@withContext 0

        var batchCorrections = 0
        val saved5min = eta5min
        val saved30min = eta30min
        val saved60min = eta60min
        eta5min  *= 2.0
        eta30min *= 2.0
        eta60min *= 2.0

        try {
            for (i in 1 until historyRecords.size) {
                val prev = historyRecords[i - 1]
                val curr = historyRecords[i]
                val dt = (curr.timestamp - prev.timestamp) / 60000.0  // 分钟

                // ★ v3.0.17 修复: dt 范围放宽到 [2, 120], 适配 xlsx 导入的稀疏数据 (之前 4-65 太严格)
                if (dt !in 2.0..120.0) continue

                // ★ 批量导入: 用简单的上下文 (无meal/insulin信息)
                val batchCtx = SnapContext(curr.value, 0.0, 0.0, Double.MAX_VALUE, 0.0, Double.MAX_VALUE, 0)
                val effectiveParams = applyDeltas(baseParams)
                // ★ v3.0.9 修: 之前永远推 5min, 但 dt 可达 65min → 误差归因错位 (餐后事件被当系统偏差学)
                //   现在按实际 dt 推, 让 batch 误差反映真实时间跨度上的预测偏差
                val syntheticPred = runOneStepRK4(effectiveParams, prev.value, 0.0, 0.0, dtMinutes = dt)
                val error = curr.value - syntheticPred

                // ★ v3.0.17 修复: 批量导入 quality 提到 0.95, 信任用户导入的历史数据
                val quality = 0.95
                val label = when { dt <= 10.0 -> "5min"; dt <= 35.0 -> "30min"; else -> "60min" }

                // ★ v3.0.17 修复: forceApply=true 跳过白噪声检查 (导入数据在累积样本上易被误判白噪声)
                val result = applyCorrection(error, curr.value, quality, baseParams, batchCtx, label, forceApply = true)
                if (result != null) batchCorrections++

                if (i % 50 == 0 || i == historyRecords.size - 1) {
                    onProgress(i + 1, historyRecords.size, batchCorrections)
                }
            }
        } finally {
            eta5min = saved5min
            eta30min = saved30min
            eta60min = saved60min
            // ★ v3.0.17 修复: 批量导入完成清空 errorHistory, 避免批量稀疏数据污染后续实时 ACF1 判白噪声
            synchronized(trackerLock) {
                errorHistory.clear()
                directionTracker.clear()
            }
        }

        // ★ 修复 Bug 6: import 完成后 dailyChanges 衰减, 避免当天剩余真实 CGM 触发被全部 clamp 到 0
        //   用户导入历史 → 当天剩余时间自学习"被吃掉"是隐性问题, 这里衰减 50% 给真实数据留余地
        synchronized(dailyChanges) {
            for (idx in dailyChanges.indices) {
                dailyChanges[idx] *= 0.5
            }
        }

        persistState()
        Log.i(TAG, "批量导入完成: ${historyRecords.size}条 → $batchCorrections 次修正 (dailyChanges已衰减50%)")
        batchCorrections
    }

    /** 获取当前状态 (供UI展示) */
    fun getStatus(): Status {
        // ★ 用内部errorHistory算MAE, 而非外部传入(之前传入空list→永远0%)
        val snapshot = synchronized(trackerLock) { errorHistory.toList() }
        val recentMAE = if (snapshot.isNotEmpty()) snapshot.map { abs(it) }.average() else 0.0

        // 误差趋势 (用内部errorHistory快照)
        val errorTrend = when {
            snapshot.size < 6 -> "收集数据中…"
            else -> {
                val firstHalf = snapshot.take(snapshot.size / 2).map { abs(it) }.average()
                val secondHalf = snapshot.drop(snapshot.size / 2).map { abs(it) }.average()
                when {
                    secondHalf < firstHalf * 0.8 -> "改善中 ↓"
                    secondHalf > firstHalf * 1.2 -> "退化 ↑"
                    else -> "稳定 →"
                }
            }
        }

        // 调整速率: 基于修正频率和方向稳定性
        // ★ 线程安全: directionTracker快照, 避免UI读取时IO写入导致ConcurrentModification
        val trackerSnapshot = synchronized(trackerLock) { directionTracker.toList() }
        val adjustmentRate = when {
            totalCorrections == 0 -> "待命中"
            totalCorrections < 10 -> "快速修正"  // 新用户/刚启动
            else -> {
                val sameStreak = maxConsecutiveSameSign(trackerSnapshot)
                val flips = trackerSnapshot.windowed(2).count { (a, b) -> a != b }
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
            recentActions = synchronized(trackerLock) { recentActions.toList() },
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
        synchronized(trackerLock) {
            errorHistory.clear()
            directionTracker.clear()
        }
        initRLSMatrix()
        eta5min = ETA_5MIN_BASE
        eta30min = ETA_30MIN_BASE
        eta60min = ETA_60MIN_BASE
        dailyChanges.fill(0.0)
        totalCorrections = 0
        correctionsToday = 0
        lastAction = null
        synchronized(trackerLock) { recentActions.clear() }
        deltas.kStomach = 0.0; deltas.vmaxGastric = 0.0; deltas.vm0 = 0.0
        deltas.vmX = 0.0; deltas.hepaticBase = 0.0; deltas.kp3 = 0.0
        persistState()
        Log.i(TAG, "EDOC已重置")
    }

    /**
     * ★ v3.0.18 新增: 仅清预测缓存, 保留 deltas/totalCorrections/P 矩阵
     *   场景: App 进后台时调用 (回前台后 PredictionScreen 会重新填充缓存)
     *   跟 reset() 的区别: reset() 还会清掉 deltas → 用户每次后台化预测都回到 baseParams
     */
    fun clearPredictionCache() {
        synchronized(predictionCache) { predictionCache.clear() }
        Log.i(TAG, "EDOC 预测缓存已清空 (deltas 保留)")
    }

    // ═══════════════════════════════════════════
    // 核心算法
    // ═══════════════════════════════════════════

    /**
     * 对单个时域做误差检查和参数修正 (实时模式：查预测缓存)
     *
     * 如果缓存为空(PredictionScreen从未打开过)→用上次读数线性外推作为回退
     * 确保EDOC即使用户只使用HomeScreen也能"每条数据纠一次"
     */
    private fun checkAndCorrect(
        predictTime: Long, actualValue: Double, quality: Double,
        baseParams: DallaManModel.Parameters, context: SnapContext, label: String, step: Int
    ): CorrectionAction? {
        // 查缓存
        val cached = synchronized(predictionCache) { findNearestPrediction(predictTime) }

        val error = if (cached != null) {
            // 有真实预测: 误差 = 实际 - 预测
            actualValue - cached.atStep(step)
        } else {
            // ★ v3.0.13: 没有预测缓存 → fallback 路径
            //   策略: 用上次读数 → 当前读数的变化率 slope, 算出"线性外推的预测值"
            //   5min 时域: 预测 = lastVal + slope * (dtMin - 5), 误差 = currentGlucose - 预测
            //   30min 时域: dtMin < 30 → 跳过 (没足够信息评估长期趋势)
            //   60min 时域: dtMin < 60 → 跳过
            //   → 确保 fallback 路径只在"有足够历史"时触发, 不会瞎纠
            val lastVal = lastReadingValue
            val lastTime = lastReadingTime
            if (lastVal.isNaN() || lastTime == 0L) return null  // 真没历史, 没法算
            val dtMs = nowSafe() - lastTime
            if (dtMs < 60_000) return null  // 至少 1 分钟间隔
            val dtMin = (dtMs / 60_000.0)
            val stepMinutes = step * 5
            if (dtMin < stepMinutes.toDouble()) return null  // 历史不够长, 跳过该时域
            val slope = (actualValue - lastVal) / dtMin  // mmol/L per min
            // 假设 glucose 匀速变化: 上次读数 → 当前读数
            // 5min 时域的"预测" = lastVal + slope * (dtMin - 5)
            //   但实际上 dtMin 通常就是 5min, (dtMin - 5) ≈ 0, 所以预测 ≈ lastVal
            //   error = currentGlucose - lastVal = slope * dtMin
            // 简化为: error = slope * stepMinutes (反映 step 时长内应有的变化幅度)
            slope * stepMinutes
        }

        return applyCorrection(error, actualValue, quality, baseParams, context, label)
    }

    /** 线程安全的当前时间 (兼容测试) */
    private fun nowSafe(): Long = System.currentTimeMillis()

    /**
     * 应用修正 (核心逻辑, 被实时模式和批量模式共用)
     */
    private fun applyCorrection(
        error: Double, actualGlucose: Double, quality: Double,
        baseParams: DallaManModel.Parameters, context: SnapContext, label: String,
        forceApply: Boolean = false
    ): CorrectionAction? {
        // ── 噪声过滤 ──
        val noiseStd = max(SENSOR_MARD * actualGlucose / 100.0, NOISE_FLOOR)
        val effectiveThreshold = noiseStd * (2.0 - quality)
        if (abs(error) < effectiveThreshold) return null

        // 异常检测
        if (abs(error) > MAX_ERROR) {
            Log.w(TAG, "异常大误差 ${String.format("%.1f", error)} mmol/L, 跳过")
            return null
        }

        // ── 误差分类 ──
        synchronized(trackerLock) {
            errorHistory.add(error)
            while (errorHistory.size > ERROR_WINDOW) errorHistory.removeAt(0)
        }
        val errorType = classifyError(errorHistory)
        // ★ v3.0.17 修复: 批量导入时 (forceApply=true) 跳过白噪声检查
        //   修复前: 导入 2592 条 → ACF1 在累积样本上算成白噪声 → 0 次修正
        //   修复后: 信任批量导入数据, 即使 ACF1 < 0.3 也强制修正
        if (errorType == "白噪声" && !forceApply) return null

        // ── 状态感知梯度 ──
        val gradValues = computeSensitivities(baseParams, context)

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
        var baseEta = when (label) {
            "5min"  -> eta5min; "30min" -> eta30min; "60min" -> eta60min; else -> eta30min
        }
        // ★ 修复 Smell 4: hourOfDay 实际生效 — 黎明现象 (4-7点) 5min 时域学习率 ×1.3
        //   此时肝糖输出脉冲 (生长激素) 致血糖上升, 模型系统性偏低, 加速学习追赶
        //   深夜 (0-4点) 略减学习率防过度反应 (Somogyi 反弹等事件)
        if (label == "5min") {
            baseEta *= when (context.hourOfDay) {
                in 4..6 -> 1.3   // 黎明现象高发期
                in 0..3 -> 0.85  // 深夜, 减少对偶发事件的过度反应
                else -> 1.0
            }
        }

        // ── Sign-based参数更新 ──
        val paramDeltas = mutableMapOf<String, Double>()
        // ★ 等权归因: 简化模型梯度幅值不可靠(单位不一致), 用sign+等权
        //   RLS协方差矩阵自适应学习哪个参数真正重要
        // ★ v3.0.10 修: 单参数时 (nActive=1) 等权公式退化为 1.0, 混合逻辑无意义
        //   修复: nActive==1 时 attribution = 1.0 跳过混合; nActive>1 才走"等权+弹性"混合
        val nActive = effectiveIndices.size.coerceAtLeast(1)
        val equalAttr = 1.0 / nActive

        for (idx in effectiveIndices) {
            val grad = gradValues[idx]
            // ★ 每个参数用自己的RLS对角线
            val rlsDiag = P[idx][idx].coerceIn(0.1, 10.0)
            // ★ 相对弹性归一化: (∂G/∂p) * (p/G) → 消除单位量级差异
            val paramBase = getBaseParam(baseParams, idx)
            val elasticity = abs(grad) * paramBase / abs(error.coerceIn(0.5, 10.0))
            val attribution = if (nActive == 1) {
                // 单参数场景 (例如 5min 时域只有 kStomach): 直接全权, 不混合
                1.0
            } else if (elasticity > 1e-9) {
                equalAttr * 0.5 + elasticity / (elasticity + 1.0) * 0.5  // 等权+弹性混合
            } else {
                equalAttr  // 弹性≈0 → 退化为等权
            }

            // Sign-SGD: delta = η × error × sign(grad) × attribution / rlsDiag
            val delta = baseEta * error * sign(grad) * attribution / rlsDiag

            // 参数限幅 (复用上面已声明的paramBase)
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

            // ★ v3.0.10 修: dailyChanges 撞上限时, 同步增大 P[idx][idx] 降低该参数的学习率
            //   修复前: finalDelta=0 但 P[i][i] 没变 → RLS 仍认为该参数可学习 → 下次误差算时仍尝试修正
            //   修复后: 撞上限时 P[i][i] × 1.5 (下次 delta 计算时分母更大, 学习率自然降低)
            if (remainingDailyBudget <= 0 && abs(paramBase) > 0) {
                P[idx][idx] = (P[idx][idx] * 1.5).coerceIn(0.01, 100.0)
            }

            deltas.add(idx, finalDelta)
            dailyChanges[idx] += abs(finalDelta)
            paramDeltas[paramName(idx)] = finalDelta
        }

        // ── 方向追踪 + 学习率自适应 ──
        synchronized(trackerLock) {
            directionTracker.add(sign(error).toInt())
            while (directionTracker.size > DIRECTION_WINDOW) directionTracker.removeAt(0)
        }
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
        lastAction = action
        synchronized(trackerLock) {
            recentActions.add(0, action)  // 最新的在前面
            while (recentActions.size > 5) recentActions.removeAt(recentActions.size - 1)
        }

        Log.d(TAG, "[$label] e=${String.format("%.1f", error)} $errorType → " +
                paramDeltas.entries.joinToString { "${it.key}=${String.format("%+.4f", it.value)}" })

        return action
    }

    // ═══════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════

    /** 在缓存中找到最匹配的预测 (容差 90 分钟, 覆盖 5min/30min/60min 三个时域)
     * ★ v3.0.12 修复: 之前 30秒容差 → onNewReading(now-5min) 永远查不到 (差 5min > 30s)
     * → fallback return null → 即时纠错永远不触发
     */
    private fun findNearestPrediction(targetTime: Long): CachedPrediction? {
        return predictionCache[targetTime]
            ?: predictionCache.entries
                .minByOrNull { abs(it.key - targetTime) }
                ?.takeIf { abs(it.key - targetTime) < 90 * 60_000L }  // 90分钟容差
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
      * 上下文感知的灵敏度分析
      *
      * 根据患者生理状态决定每个参数是否参与修正:
      *   - 空腹: kStomach/VmaxGastric灵敏度归零 (没食物→胃排空参数无关)
      *   - 无胰岛素: VmX/kp3灵敏度归零 (没胰岛素→胰岛素效应参数无关)
      *   - 用实际血糖而非固定7.0 (MM动力学的血糖依赖性)
      *   - 用实际胃内容而非固定30g (反映真实消化状态)
      *
      * ★ v3.0.10 修: 之前用 applyDeltas(basePos) 跑 RK4 (含 EDOC 累积 delta)
      *   → feedback loop: EDOC 算自己的 delta 对当前误差的"贡献度", 再更新 delta → 反馈震荡
      *   修复: 微扰直接在 base 上, 不用 applyDeltas
      */
    private fun computeSensitivities(
        baseParams: DallaManModel.Parameters, context: SnapContext
    ): DoubleArray {
        val sensitivities = DoubleArray(PARAM_COUNT)
        val eps = 0.01
        val g = context.currentGlucose.coerceIn(3.0, 20.0)

        // 胃内容物: 根据近期碳水量估算
        val assumedStomach = when {
            context.recentCarbs > 50 -> 50_000.0   // 大餐
            context.recentCarbs > 20 -> 25_000.0   // 中餐
            context.recentCarbs > 5  -> 10_000.0   // 小餐/零食
            else -> 0.0                            // 空腹→kStomach/VmaxGastric不参与
        }

        // 胰岛素效应: IOB>0.1U时启用VmX/kp3灵敏度
        val iob = context.iob

        for (i in 0 until PARAM_COUNT) {
            // ★ 状态过滤: 不相关的参数直接跳过
            if (i == IDX_KSTOMACH || i == IDX_VMAXGASTRIC) {
                if (assumedStomach == 0.0) continue  // 空腹→灵敏度=0
            }
            if (i == IDX_VMX || i == IDX_KP3) {
                if (iob < 0.1) continue  // 无胰岛素→灵敏度=0
            }

            val baseVal = getBaseParam(baseParams, i)
            val absEps = max(abs(baseVal) * eps, 1e-6)

            // ★ v3.0.10: 微扰直接在 base 上 (不用 applyDeltas), 避免 EDOC 自己的 delta 反馈
            //   灵敏度表示"参数本身对预测的影响", delta 是累积的偏移, 不应该被算进去
            val basePos = setBaseParamOnly(baseParams, i, baseVal * (1.0 + eps))
            val predPos = runOneStepRK4(basePos, g, assumedStomach, iob)

            val baseNeg = setBaseParamOnly(baseParams, i, baseVal * (1.0 - eps))
            val predNeg = runOneStepRK4(baseNeg, g, assumedStomach, iob)

            sensitivities[i] = (predPos - predNeg) / (2.0 * absEps)
        }

        return sensitivities
    }

    /**
     * 一步预测 (上下文感知)
     *
     * ★ v3.0.18 修: 量纲混乱 — 之前 ra/egp/uii 单位混用 (mg/min 和 mmol/L/min), 导致
     *   5min 偏差 0.59 mmol/L, EDOC 批量学习把这个偏差当"DallaMan 系统误差"持续累积到 deltas.
     *   修复: 全部项统一为 mmol/L/min (跟 DallaManModel.predict 完全一致).
     */
    private fun runOneStepRK4(
        params: DallaManModel.Parameters, glucose: Double,
        assumedStomach: Double, iob: Double,
        dtMinutes: Double = 5.0
    ): Double {
        val dt = dtMinutes
        val g = glucose.coerceIn(2.0, 35.0)
        val Vg = params.Vg  // dL
        val BW = params.bodyWeight
        val VgDl18 = Vg * 18.0  // mg/dL per mmol/L 转换系数

        // Ra (mmol/L/min): 肠道葡萄糖吸收入血
        val raMmol = if (assumedStomach > 0) {
            val emptying = min(params.kStomach * assumedStomach, params.VmaxGastric * BW)
            emptying * params.fCarbs / VgDl18
        } else 0.0

        // Uii (mmol/L/min): 非胰岛素依赖利用, 与血糖偏离基础值成正比
        val uii = params.k1 * (g - params.Gb)

        // Uid (mmol/L/min): 胰岛素依赖利用, MM 方程
        val G_mgdl = g * 18.0
        val vm = if (iob > 0.1) params.Vm0 + params.VmX * (iob / 10.0).coerceIn(0.0, 1.0)
                 else params.Vm0
        val uid = vm * G_mgdl / (params.Km0 + G_mgdl) * BW / VgDl18

        // EGP (mmol/L/min): 肝糖内生产出 (基础值, 简化版不考虑 X_L 抑制)
        val egp = params.hepaticBase * BW / VgDl18

        val dg_dt = raMmol + egp - uii - uid
        return (g + dg_dt * dt).coerceIn(2.0, 35.0)
    }

    /** 自适应学习率: 同向加速, 翻转减速 */
    private fun adjustLearningRates(label: String) {
        val trackerSnapshot = synchronized(trackerLock) { directionTracker.toList() }
        if (trackerSnapshot.size < DIRECTION_WINDOW / 2) return

        val sameStreak = maxConsecutiveSameSign(trackerSnapshot)
        val flips = trackerSnapshot.windowed(2).count { (a, b) -> a != b }

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
        return try {
            val onlineParams = SelfLearningManager.getOnlineLearner().getPersonalParams()
            val dataDays = onlineParams.dataDays
            val progress = (dataDays / LAMBDA_DAYS).coerceIn(0.0, 1.0)
            LAMBDA_INITIAL + (LAMBDA_FINAL - LAMBDA_INITIAL) * progress
        } catch (e: Exception) {
            LAMBDA_INITIAL  // 降级: SelfLearningManager未就绪时用初始值
        }
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
