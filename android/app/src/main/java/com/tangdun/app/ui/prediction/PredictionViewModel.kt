package com.tangdun.app.ui.prediction

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.*
import com.tangdun.app.data.local.entity.ExerciseRecord
import com.tangdun.app.domain.algorithm.*
import com.tangdun.app.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.pow

data class PredictionUiState(
    val isLoading: Boolean = true,
    val currentGlucose: Double? = null, val predicted30min: Double? = null,
    val predicted60min: Double? = null, val predicted120min: Double? = null,
    val riskLevel: String = "正常",
    val curve: List<Double> = emptyList(),          // 最终融合预测
    val physioCurve: List<Double> = emptyList(),     // DallaMan生理模型
    val tcnCurve: List<Double> = emptyList(),         // ★ v3.0.8 TCN 模型单独预测线 (替换原来的增量残差)
    val historyData: List<Pair<Long, Double>> = emptyList(),
    val modelLabel: String = "", val predictionTime: String = "",
    val confidence: Double = 0.0, val totalRecords: Int = 0,
    val fastingBaseline: Double = 0.0, val variability: Double = 0.0,
    val activeInsulin: Double = 0.0, val todayCarbs: Double = 0.0,
    val targetLow: Double = 3.9, val targetHigh: Double = 10.0,
    val tcnWeight: Double = 0.0, val physioWeight: Double = 0.0,
    val personalizationWeight: Double = 0.0,    // ★ 个性化权重 (基于 incUpdates + dataCompleteness, 永远至少 5%)
    val peakValue: Double = 0.0, val peakMinute: Int = 0,
    val isfEstimate: Double = 1.5, val crEstimate: Double = 12.0,
    val historyHours: Int = 3,
    val error: String? = null
)

@HiltViewModel
class PredictionViewModel @Inject constructor(
    @ApplicationContext private val ctx: android.content.Context,
    private val glucoseDao: GlucoseDao, private val insulinDao: InsulinDao,
    private val mealDao: MealDao, private val exerciseDao: ExerciseDao,
    private val settings: SettingsManager
) : ViewModel() {

    companion object { private const val TAG = "PredVM" }

    private val _uiState = MutableStateFlow(PredictionUiState())
    val uiState = _uiState.asStateFlow()

    private val predictor = PersonalizedPredictor(ctx)
    private val physiological = DallaManModel()
    private val tcnOk = predictor.initialize()

    // 从SelfLearningManager读取共享实例 (避免重复创建)
    private val onlineLearner get() = SelfLearningManager.getOnlineLearner()
    private val cgmCalibrator by lazy { com.tangdun.app.domain.algorithm.CGMCalibrator(ctx) }

    init {
        Log.i(TAG, "TCN=${if (tcnOk) "ONNX" else "降级"}")
        loadPrediction()
        // 新血糖 → 重算
        viewModelScope.launch { glucoseDao.getLatestFlow().filterNotNull().distinctUntilChanged { o, n -> o.timestamp == n.timestamp }.debounce(2000).collect { loadPrediction() } }
        // 新饮食记录 → 重算 (碳水影响预测)
        viewModelScope.launch { mealDao.getRecentFlow(1).distinctUntilChanged { o, n -> o.firstOrNull()?.id == n.firstOrNull()?.id }.debounce(1500).collect { loadPrediction() } }
        // 新胰岛素记录 → 重算 (IOB影响预测)
        viewModelScope.launch { insulinDao.getRecentFlow(1).distinctUntilChanged { o, n -> o.firstOrNull()?.id == n.firstOrNull()?.id }.debounce(1500).collect { loadPrediction() } }
        // 运动记录 → 重算
        viewModelScope.launch { exerciseDao.getRecentFlow(1).distinctUntilChanged { o, n -> o.firstOrNull()?.id == n.firstOrNull()?.id }.debounce(1500).collect { loadPrediction() } }
        // 设置变化 → 完整重算（阈值影响风险判定）
        viewModelScope.launch { combine(settings.targetLow, settings.targetHigh) { low, high -> Pair(low, high) }.distinctUntilChanged().collect { loadPrediction() } }
    }

    fun refresh() { loadPrediction() }
    fun setHistoryHours(hours: Int) {
        _uiState.value = _uiState.value.copy(historyHours = hours)
        loadPrediction()
    }

    private fun loadPrediction() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // 根据选择的历史窗口获取数据 (默认3h=36点)
                val histHours = _uiState.value.historyHours
                val histPoints = histHours * 12  // 每小时12个5分钟点
                val allRecords = glucoseDao.getRecent(maxOf(histPoints, 288)).reversed()
                val records = allRecords.takeLast(histPoints)
                if (records.size < 3) { _uiState.value = _uiState.value.copy(isLoading = false, error = "数据不足，等待血糖数据..."); return@launch }
                val g = records.last().value; val gh = allRecords.map { it.value }.toDoubleArray() // TCN用全288点
                val now = System.currentTimeMillis()
                val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

                // 用户数据（24小时范围）
                val meals24h = mealDao.getByTimeRange(now - 24 * 3600 * 1000, now)
                val insulin = insulinDao.getSince(now - 24 * 3600 * 1000)
                val todayCarbs = meals24h.sumOf { it.totalCarbs }
                // ★ v3.0.16 物理门控: 改用过去 2h 碳水 (不再用 24h 累计, 避免 4h 前吃过的饭误触发上扬门控)
                val recentCarbs2h = meals24h.filter { it.timestamp >= now - 2 * 3600 * 1000 }.sumOf { it.totalCarbs }
                // IOB: 速效(4h半衰55min) + 短效(6h半衰90min)
                val iob = insulin.fold(0.0) { a, r ->
                    val m = (now - r.timestamp) / 60000.0
                    when (r.insulinType) {
                        "rapid" -> if (m in 0.0..240.0) a + r.doseUnits * 0.5.pow(m / 55.0) else a
                        "short" -> if (m in 0.0..360.0) a + r.doseUnits * 0.5.pow(m / 90.0) else a
                        else -> a
                    }
                }

                // 运动数据 — 影响血糖预测(运动增加葡萄糖利用)
                val exercises = exerciseDao.getTodayRecords(todayStart)
                // 运动降糖效应: 每30分钟中等强度运动约降0.5-1.0 mmol/L
                val exerciseEffect = if (exercises.isNotEmpty()) {
                    val totalMinutes = exercises.sumOf { it.durationMin ?: 0 }
                    totalMinutes * 0.02  // 约0.02 mmol/L per minute of exercise
                } else 0.0

                // 自动测算 + 自动更新用户设置 (样本≥15时可信)
                // ★ 修复 Smell 2: 默认不自动覆盖用户 ISF/CR, 用户在 Settings 主动开启才覆盖
                //   避免医生/谨慎用户被悄悄改设置
                val est = AutoParamEstimator.estimate(glucoseDao.getRecent(500), insulinDao.getRecent(300), mealDao.getRecent(100))
                if (settings.isAutoParamEstimateEnabled() &&
                    (est.confidence == "高" || est.confidence == "中")) {
                    settings.setInsulinSensitivity(est.insulinSensitivity.toFloat())
                    settings.setCarbRatio(est.carbRatio.toFloat())
                }

                // Dalla Man 生理模型: 24h进食 + 24h胰岛素
                val weight = settings.getWeightKg().toDouble()

                // 饮食输入: 直接用真实过去分钟数 (避免 clamp 强行消耗胃内容物)
                // ★ v3.0.10 修: 之前 rawMinutes<15 时 maxOf(rawMinutes, 10.0), 把 3 分钟前的饭当 10 分钟前
                //   强行消耗胃内容物导致 DallaMan 低估当前实际胃里的食物量 → 餐后立即预测偏低
                //   现在: 直接用 rawMinutes, 1 分钟前就传 1 分钟前
                val mealInputs = meals24h.takeLast(5).map {
                    val rawMinutes = ((now - it.timestamp) / 60000.0).coerceIn(0.0, 240.0)
                    DallaManModel.MealInput(rawMinutes, it.totalCarbs, it.avgGi)
                }
                // 胃排空速率按加权GI调整 (高GI→快排空, 低GI/高脂→慢排空)
                val avgGi = if (mealInputs.isNotEmpty()) mealInputs.map { it.gi }.average() else 50.0
                val giFactor = (avgGi / 50.0).coerceIn(0.7, 1.5)
                // 长效胰岛素: 指数加权→提高Ib (半衰12h, 最近剂量权重大)
                val longBasalBoost = insulin.filter { it.insulinType == "long" || it.insulinType == "long-acting" }
                    .sumOf { it.doseUnits * 0.5.pow(((now - it.timestamp) / 3600000.0) / 12.0) } * 0.4

                // ★ 全部个性化: 空腹基线/ISF/活动量/体重全部从用户数据读取
                val isf = settings.getInsulinSensitivity().toDouble()
                // ★ 修复 Smell 5: sigma 应该反映 β 细胞残余功能 (与糖尿病类型/病程相关),
                //   之前用 ISF 反推是把两件正交的事绑一起, 临床不合理
                //   T1DM=0, T2DM 早期=4-6, T2DM 中晚期=2-4 (Dalla Man 2007 文献默认值)
                val diabetesType = settings.getDiabetesType()  // 1=T1DM, 2=T2DM
                val dynSigma = when (diabetesType) {
                    1 -> 0.0  // T1DM: 无内源胰岛素分泌
                    else -> 3.0  // T2DM 默认 (中晚期; 早期可让用户在 Settings 调高 sigma)
                }
                val fastingGlucose = onlineLearner.getPersonalParams().fastingBaseline
                val basalI = (8.0 + longBasalBoost).coerceIn(4.0, 30.0)
                // 活动量: 7天滚动平均运动时长 (30min/天→0.7, 久坐→0.3)
                val recentExercises = exerciseDao.getByTimeRange(now - 7 * 24 * 3600 * 1000L, now)
                val avgDailyMin = if (recentExercises.isNotEmpty())
                    recentExercises.sumOf { it.durationMin ?: 0 } / 7.0 else 0.0
                val activityLevel = (0.35 + avgDailyMin / 60.0 * 0.5).coerceIn(0.3, 0.8)
                val dmParams = DallaManModel.Parameters.forUser(
                    bodyWeight = weight, fastingGlucose = fastingGlucose,
                    isf = isf, basalInsulin = basalI, sigma = dynSigma,
                    activityLevel = activityLevel
                )
                // GI调整胃排空
                val adjKStomach = (dmParams.kStomach * giFactor).coerceIn(0.025, 0.080)
                val baseParams = dmParams.copy(kStomach = adjKStomach)

                // ★ EDOC: 应用即时纠错累积的参数修正 → 预测用修正后参数
                val edocCorrector = SelfLearningManager.getEDOCCorrector()
                val finalParams = if (edocCorrector.getStatus().isActive) {
                    edocCorrector.applyDeltas(baseParams)
                } else {
                    baseParams
                }

                // ★ 传给EDOC的是BASE参数(不含修正), EDOC在上面叠加deltas
                SelfLearningManager.setBaseParams(baseParams)

                // 速效/短效胰岛素: 皮下bolus建模
                val insulinInputs = insulin.filter { it.insulinType == "rapid" || it.insulinType == "short" }.takeLast(10).map { DallaManModel.InsulinInput((now - it.timestamp) / 60000.0, it.doseUnits) }
                // 更新数据质量标记
                onlineLearner.updateDataCompleteness(mealInputs.isNotEmpty(), insulinInputs.isNotEmpty())

                val dmCurve = physiological.predict(g, maxOf(iob * 15.0, 5.0), mealInputs, insulinInputs, horizonMinutes = 180, stepMinutes = 5, params = finalParams)

                // 个性化校正
                val olParams = onlineLearner.getPersonalParams()
                val personalizedCurve = dmCurve.mapIndexed { i, v ->
                    // ★ v3.0.16 修: OnlineLearner.applyPersonalization 不再接收 currentGlucose (dead parameter)
                    val personal = onlineLearner.applyPersonalization(v, i)
                    if (exerciseEffect > 0) personal - exerciseEffect * kotlin.math.exp(-i * 5.0 / 120.0)
                    else personal
                }

                // TCN增强
                var tcnW = 0.0
                var personalizationW = 0.0
                var modelLabel = "DallaMan(ISF${"%.1f".format(isf)} ${mealInputs.size}餐 ${insulinInputs.size}针 ${"%.0f".format(weight)}kg)"
                if (!tcnOk) modelLabel += " [TCN未加载]"

                // ★ Path A 主线: 生理曲线 (含 EDOC 实时修正 + 用户实际饮食/胰岛素)
                val physioCurve = personalizedCurve.toMutableList().apply { this[0] = g }

                var merged: List<Double> = physioCurve
                // ★ v3.0.8: 把 bh/ch 提到外层, 让下面的 TCN 单独预测也能用
                val bh = DoubleArray(288) { 0.0 }; val ch = DoubleArray(288) { 0.0 }
                for (r in insulin) { val i = (287 - ((now - r.timestamp) / 300000).toInt()); if (i in 0..287) bh[i] += r.doseUnits }
                for (m in meals24h) { val i = (287 - ((now - m.timestamp) / 300000).toInt()); if (i in 0..287) ch[i] += m.totalCarbs }

                if (tcnOk && records.size >= 10) {
                    try {
                        // ★ 只跑 TCN, 不让 PersonalizedPredictor 重算 DallaMan (避免 Path B 重复)
                        val tcnRawCurve = predictor.predictTCNOnly(gh, g, bh, ch)
                        val nPoints = minOf(tcnRawCurve?.size ?: 0, physioCurve.size)
                        if (tcnRawCurve != null && nPoints >= 25) {
                            // ★ TCN曲线配准: 对齐起点到当前血糖 (消除d参数偏移)
                            val tcnOffset = tcnRawCurve[0] - g
                            var alignedTcn = tcnRawCurve.map { it - tcnOffset }

                            // ★ v3.0.14 物理约束后处理: TCN 模型实测 c 项恒正 (无论 IOB 都预测上升)
                            //   实测: f10=8U 时 TCN 仍预测 30min +1.8% ↑, 必须 f10>=20U 才开始预测下降
                            //   这是 ONNX 模型本身的 bug, app 端通过"实时事件门控"补偿:
                            //   - IOB > 1U 且无 carb 时: 强制 TCN 输出按 DallaMan slope 衰减 (TCN 不可信)
                            //   - carb 1h > 30g 时: 强制 TCN 输出按 DallaMan shape 上扬 (TCN 不可信)
                            val horizon30 = 6  // 30min = 6 个 5min 步长
                            val physioSlope30 = if (physioCurve.size > horizon30) (physioCurve[horizon30] - g) / 30.0 else 0.0  // mmol/min
                            val tcnSlope30 = if (alignedTcn.size > horizon30) (alignedTcn[horizon30] - g) / 30.0 else 0.0

                            // 物理门控: 高 IOB 时 TCN 不可信, 用 DallaMan slope 替代 TCN slope
                            val physicalOverride = when {
                                // 情况1: 高 IOB + 低 carb → TCN 应该预测降, 但 ONNX 学不会 → 用生理 slope
                                iob > 1.0 && recentCarbs2h < 30 -> true
                                // 情况2: TCN 方向和 DallaMan 反向 + 物理约束强烈 (IOB 大)
                                iob > 2.0 && tcnSlope30 > 0.02 && physioSlope30 < -0.02 -> true
                                // 情况3: 高 carb 摄入 → TCN 应该预测升, 但 ONNX 预测幅度不够
                                recentCarbs2h >= 30 && tcnSlope30 < 0.02 -> true
                                else -> false
                            }

                            if (physicalOverride) {
                                // ★ 用 DallaMan 30min 内 slope 重建 TCN 曲线 (保留起点对齐)
                                // alignedTcn[i] = g + physioSlope30 * 0.6 * i * 5 (60% 物理 slope, 避免过度修正)
                                alignedTcn = alignedTcn.mapIndexed { i, _ ->
                                    val tMin = i * 5.0
                                    g + physioSlope30 * 0.6 * tMin
                                }
                                Log.w(TAG, "TCN 物理门控触发: IOB=${"%.1f".format(iob)} carb=${todayCarbs}g, " +
                                    "用 DallaMan slope ${"%.3f".format(physioSlope30)} mmol/min 替代 TCN")
                            }

                            // ★ BMA 三模型权重 (个性化权重基于数据完整度 + 增量更新次数, 永远至少 5%, 最多 30%)
                            val totalRecords = glucoseDao.getCount()
                            val incStats = SelfLearningManager.getIncrementalLearner().getStats()
                            val incUpdates = incStats["updates"] as? Int ?: 0
                            val dataCompleteness = onlineLearner.getPersonalParams().dataCompleteness
                            personalizationW = (0.05 + 0.05 * (incUpdates / 100.0) + 0.10 * dataCompleteness).coerceIn(0.05, 0.30)

                            // TCN + DallaMan 在剩余 (1 - personalizationW) 中按数据量分配
                            val baseTcnRatio = (0.3 + 0.4 * totalRecords / 288.0).coerceIn(0.3, 0.7)
                            // ★ v3.0.14 物理门控后: 门控触发时 tcnRatio 强制降到 0.2 (信任生理)
                            val tcnRatio = if (physicalOverride) 0.2 else baseTcnRatio
                            val remain = 1.0 - personalizationW
                            tcnW = tcnRatio * remain

                            // ★ v3.0.16 BMA 公式修复: 之前 (1-pw) 系数包了整段 baseBlend, pw 那部分完全没体现
                            //   正确公式: pw*physio + (1-pw)*(tcnRatio*tcn + (1-tcnRatio)*physio)
                            //           = (1-pw)*tcnRatio*tcn + ((1-pw)*(1-tcnRatio) + pw) * physio
                            //           总系数 = 1.0 (跟注释 "TCN_w + DallaMan_w = 1.0" 对齐)
                            merged = (0 until nPoints).map { i ->
                                val tcnPart = (1.0 - personalizationW) * tcnRatio
                                val physioPart = 1.0 - tcnPart
                                (tcnPart * alignedTcn[i] + physioPart * physioCurve[i]).coerceIn(1.0, 30.0)
                            }
                            // 个性化层后续通过 applyPersonalization 叠加
                            modelLabel = if (physicalOverride) {
                                "🔧物理门控TCN(IOB${"%.1f".format(iob)})信任生理"
                            } else {
                                "TCN+DallaMan+个性化(${(personalizationW*100).toInt()}% ${mealInputs.size}餐 ${insulinInputs.size}针)"
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "TCN异常: ${e.message}") }
                }

                // ★ v3.0.8: TCN 模型单独预测线 (用于 UI 三线对比)
                // 之前用"增量残差"作为第三线, 但残差在 t=0 处恒为 0, 看起来像从 0 突然上升然后衰减, 让用户误解为"AI 预测大幅度下降"
                // 现在改为 TCN 模型自己跑出来的曲线, 是真实的预测轨迹
                // ★ v3.0.14 修复: TCN 实际只输出 25 点 (0-120min), 3h tab 显示 36 点 (0-180min), 必须补全到 futureLen
                var tcnCurve: List<Double> = emptyList()
                try {
                    val tcnResult = predictor.predictTCNOnly(gh, g, bh, ch)
                    if (tcnResult != null && tcnResult.size >= 25) {
                        // 配准起点到当前血糖
                        val tcnOffset = tcnResult[0] - g
                        val alignedTcn = tcnResult.map { it - tcnOffset }

                        // ★ 物理门控同样应用到 tcnCurve (UI 显示线)
                        val horizon30 = 6
                        val physioSlope30 = if (physioCurve.size > horizon30) (physioCurve[horizon30] - g) / 30.0 else 0.0
                        val physicalOverrideDisplay = when {
                            iob > 1.0 && recentCarbs2h < 30 -> true
                            iob > 2.0 && (alignedTcn.getOrNull(horizon30) ?: g) > g + 0.5 && physioSlope30 < -0.02 -> true
                            recentCarbs2h >= 30 && (alignedTcn.getOrNull(horizon30) ?: g) < g + 1.0 -> true
                            else -> false
                        }
                        val correctedTcn = if (physicalOverrideDisplay) {
                            alignedTcn.mapIndexed { i, _ -> g + physioSlope30 * 0.6 * i * 5.0 }
                        } else alignedTcn

                        // ★ 补全到 futureLen 个点: 25 点之后用最后一个点的延伸 (线性外推)
                        val futureLen = physioCurve.size
                        tcnCurve = if (correctedTcn.size < futureLen) {
                            val lastVal = correctedTcn.last()
                            val lastIdx = correctedTcn.size - 1
                            val stepVal = if (lastIdx > 0) correctedTcn[lastIdx] - correctedTcn[lastIdx - 1] else 0.0
                            val extended = mutableListOf<Double>()
                            extended.addAll(correctedTcn)
                            for (i in 1..(futureLen - correctedTcn.size)) {
                                extended.add((lastVal + stepVal * i).coerceIn(1.0, 30.0))
                            }
                            extended
                        } else {
                            correctedTcn.take(futureLen)
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "TCN 曲线计算失败: ${e.message}") }
                // 兜底: 如果 TCN 没拿到, 用 physioCurve 副本 (保证 UI 不会空白)
                if (tcnCurve.isEmpty()) tcnCurve = physioCurve.toList()

// ★ v3.0.9 修: personalization 双叠加问题
                //   修复前: physioCurve 已叠加过 applyPersonalization, finalCurve 又叠一次 → 1.7 倍
                //   修复后: finalCurve 只叠 "时段模式 + 残差" 两个独立项 (不再叠 OnlineLearner 偏移)
                val finalCurve = try {
                    applyLightPersonalization(merged, g).toMutableList().apply { this[0] = g }
                } catch (e: Exception) {
                    Log.w(TAG, "轻量化个性化失败, 使用纯 merged: ${e.message}")
                    merged.toMutableList().apply { this[0] = g }
                }

                val anchored = finalCurve

                val riskLabel = when {
                    anchored.getOrNull(6)?.let { it < settings.getTargetLow().toDouble() } == true -> "低血糖风险"
                    anchored.getOrNull(6)?.let { it > settings.getTargetHigh().toDouble() } == true -> "高血糖风险"
                    else -> "正常"
                }

                val peak = anchored.max(); val pi = anchored.indexOf(peak)
                val totalRecords = glucoseDao.getCount()
                val calConf = if (cgmCalibrator.getCount() >= 1) 10.0 else 0.0
                val qualityBonus = (onlineLearner.getPersonalParams().dataCompleteness * 10).toInt().toDouble()
                val confidence = when {
                    tcnOk && totalRecords >= 5000 -> 90.0
                    tcnOk && totalRecords >= 2000 -> 80.0
                    totalRecords >= 1000 -> 65.0 + calConf + qualityBonus
                    totalRecords >= 200 -> 55.0 + calConf
                    totalRecords >= 50 -> 45.0 + calConf
                    else -> 30.0
                }

                _uiState.value = PredictionUiState(
                    isLoading = false, currentGlucose = g, riskLevel = riskLabel,
                    predicted30min = anchored.getOrNull(6), predicted60min = anchored.getOrNull(12), predicted120min = anchored.getOrNull(24),
                    curve = anchored, physioCurve = physioCurve, tcnCurve = tcnCurve,
                    historyData = records.map { it.timestamp to it.value }, modelLabel = modelLabel, predictionTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                    confidence = confidence,
                    totalRecords = totalRecords, fastingBaseline = olParams.fastingBaseline, variability = olParams.glucoseVariability,
                    activeInsulin = iob, todayCarbs = todayCarbs, tcnWeight = tcnW, physioWeight = (1 - tcnW - personalizationW).coerceAtLeast(0.0),
                    personalizationWeight = personalizationW,
                    targetLow = settings.getTargetLow().toDouble(), targetHigh = settings.getTargetHigh().toDouble(),
                    peakValue = peak, peakMinute = pi * 5,
                    isfEstimate = est.insulinSensitivity, crEstimate = est.carbRatio, error = null
                )
                // ★ EDOC: 缓存"纯 DallaMan + EDOC 修正"的曲线 (不含 TCN/在线个性化/增量残差)
                // 修复前: 缓存的是 merged (含 TCN + 增量残差), EDOC 会把这些误差错误归因到 DallaMan 参数
                // 修复后: EDOC 只看 DallaMan 自己预测的偏差, 修正更精准
                SelfLearningManager.storePrediction(g.toFloat(), physioCurve.map { it.toFloat() }.toFloatArray())

                Log.i(TAG, "预测: ${String.format("%.1f", g)} IOB${String.format("%.1f", iob)} 碳水${String.format("%.0f", todayCarbs)} 模型=$modelLabel ISF≈${String.format("%.1f", est.insulinSensitivity)} CR≈${String.format("%.1f", est.carbRatio)}")
            } catch (e: Exception) { Log.e(TAG, "预测失败: ${e.message}", e); _uiState.value = _uiState.value.copy(isLoading = false, error = "预测失败: ${e.message}") }
        }
    }

    /**
     * ★ v3.0.9 轻量化个性化叠加 — 只叠时段模式 + 残差, 不再叠 OnlineLearner 偏移
     *   原因: physioCurve 已叠过一次 applyPersonalization, finalCurve 再叠就 1.7 倍
     *   行为: 时段模式 (hourlyDev, 24h 周期) + IncrementalLearner 残差 (TCN 残差网络输出)
     */
    private suspend fun applyLightPersonalization(
        baseCurve: List<Double>,
        currentGlucose: Double
    ): List<Double> {
        if (baseCurve.isEmpty()) return baseCurve
        val n = baseCurve.size
        val tMax = if (n > 1) (n - 1).toDouble() else 1.0

        // 时段模式 (24h 时段偏离基线)
        val hourlyDev = onlineLearner.getHourlyDeviation(
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        )

        // 残差 (从 IncrementalLearner forward)
        val incLearner = SelfLearningManager.getIncrementalLearner()
        val incStats = incLearner.getStats()
        val incUpdates = incStats["updates"] as? Int ?: 0
        val hasInc = incUpdates > 20
        val incWeight = minOf(incUpdates.toDouble() / 300.0, 0.4)

        val residual = if (hasInc) {
            val gh = try { glucoseDao.getRecent(288).reversed().map { it.value }.toDoubleArray() }
                     catch (_: Exception) { DoubleArray(0) }
            if (gh.size >= 10) {
                val features = com.tangdun.app.domain.algorithm.FeatureExtractor().extract(gh, gh.size - 1)
                incLearner.forward(features)
            } else null
        } else null

        return baseCurve.mapIndexed { i, v ->
            var a = v
            if (hourlyDev != 0.0 && i < n) {
                a += hourlyDev * kotlin.math.exp(-i * 5.0 / 60.0)
            }
            if (residual != null && hasInc) {
                val t = (i / tMax).coerceIn(0.0, 1.0)
                a += currentGlucose * (residual[0]*t*t*t + residual[1]*t*t + residual[2]*t + residual[3]) * incWeight
            }
            a.coerceIn(1.0, 30.0)
        }
    }
}
