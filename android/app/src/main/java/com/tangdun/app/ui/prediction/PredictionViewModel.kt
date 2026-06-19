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
    val incrementalCurve: List<Double> = emptyList(), // 增量自学习残差
    val historyData: List<Pair<Long, Double>> = emptyList(),
    val modelLabel: String = "", val predictionTime: String = "",
    val confidence: Double = 0.0, val totalRecords: Int = 0,
    val fastingBaseline: Double = 0.0, val variability: Double = 0.0,
    val activeInsulin: Double = 0.0, val todayCarbs: Double = 0.0,
    val targetLow: Double = 3.9, val targetHigh: Double = 10.0,
    val tcnWeight: Double = 0.0, val physioWeight: Double = 0.0,
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

                // 饮食输入: 最小消化10分钟确保Ra>Uid
                val mealInputs = meals24h.takeLast(5).map {
                    val rawMinutes = (now - it.timestamp) / 60000.0
                    val effectiveMinutes = if (rawMinutes < 15.0) maxOf(rawMinutes, 10.0) else rawMinutes
                    DallaManModel.MealInput(effectiveMinutes, it.totalCarbs, it.avgGi)
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
                    val personal = onlineLearner.applyPersonalization(v, g, i)
                    if (exerciseEffect > 0) personal - exerciseEffect * kotlin.math.exp(-i * 5.0 / 120.0)
                    else personal
                }

                // TCN增强
                var tcnW = 0.0
                var modelLabel = "DallaMan(ISF${"%.1f".format(isf)} ${mealInputs.size}餐 ${insulinInputs.size}针 ${"%.0f".format(weight)}kg)"
                if (!tcnOk) modelLabel += " [TCN未加载]"

                // ★ Path A 主线: 生理曲线 (含 EDOC 实时修正 + 用户实际饮食/胰岛素)
                val physioCurve = personalizedCurve.toMutableList().apply { this[0] = g }

                var merged: List<Double> = physioCurve
                if (tcnOk && records.size >= 10) {
                    try {
                        val bh = DoubleArray(288) { 0.0 }; val ch = DoubleArray(288) { 0.0 }
                        for (r in insulin) { val i = (287 - ((now - r.timestamp) / 300000).toInt()); if (i in 0..287) bh[i] += r.doseUnits }
                        for (m in meals24h) { val i = (287 - ((now - m.timestamp) / 300000).toInt()); if (i in 0..287) ch[i] += m.totalCarbs }

                        // ★ 只跑 TCN, 不让 PersonalizedPredictor 重算 DallaMan (避免 Path B 重复)
                        val tcnRawCurve = predictor.predictTCNOnly(gh, g, bh, ch)
                        val nPoints = minOf(tcnRawCurve?.size ?: 0, physioCurve.size)
                        if (tcnRawCurve != null && nPoints >= 25) {
                            // ★ TCN曲线配准: 对齐起点到当前血糖 (消除d参数偏移)
                            val tcnOffset = tcnRawCurve[0] - g
                            val alignedTcn = tcnRawCurve.map { it - tcnOffset }

                            // ★ BMA 权重: 数据越多→TCN权重越高, 上限 0.7 (DallaMan 保留 30% 兜底)
                            val totalRecords = glucoseDao.getCount()
                            tcnW = (0.3 + 0.4 * totalRecords / 288.0).coerceIn(0.3, 0.7)

                            merged = (0 until nPoints).map { i ->
                                (tcnW * alignedTcn[i] + (1 - tcnW) * physioCurve[i]).coerceIn(1.0, 30.0)
                            }
                            modelLabel = "TCN+DallaMan(7室 ${"%.0f".format(weight)}kg ${mealInputs.size}餐 ${insulinInputs.size}针)"
                        }
                    } catch (e: Exception) { Log.w(TAG, "TCN异常: ${e.message}") }
                }

                // ★ 增量自学习残差曲线 (单独计算, 仅用于显示, 不叠加到 merged)
                // 修复前: 残差在 PersonalizedPredictor 内部叠加一次, 又在 Path A 叠加一次, 实际权重翻倍
                // 修复后: 残差只在 PersonalizedPredictor.applyPersonalization 里叠加到 personalizedCurve,
                //        这里只计算一份独立的增量曲线供 UI 三线显示用
                var incCurve: List<Double> = emptyList()
                val finalCurve = try {
                    // 应用个性化 + 增量残差叠加到 BMA 融合后的曲线
                    predictor.applyPersonalization(merged, g, gh).toMutableList().apply { this[0] = g }
                } catch (e: Exception) {
                    Log.w(TAG, "个性化叠加失败, 使用纯 merged: ${e.message}")
                    merged.toMutableList().apply { this[0] = g }
                }

                // 单独的增量残差曲线 (供 UI 三线显示)
                try {
                    val incLearner = SelfLearningManager.getIncrementalLearner()
                    val incStats = incLearner.getStats()
                    val incUpdates = incStats["updates"] as? Int ?: 0
                    if (allRecords.size >= 50) {
                        val fe = FeatureExtractor()
                        val glucoseHistory = allRecords.map { it.value }.toDoubleArray()
                        val idx = glucoseHistory.size - 1  // 当前点
                        val features = fe.extract(glucoseHistory, idx)
                        if (features.any { it != 0f }) {
                            val residual4 = incLearner.forward(features)
                            val residualWeight = minOf(incUpdates / 300.0, 0.4)
                            incCurve = (0 until 36).map { i ->
                                val t = i / 36.0
                                g * (residual4[0] * t*t*t + residual4[1] * t*t + residual4[2] * t + residual4[3]) * residualWeight
                            }
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "增量残差曲线计算失败: ${e.message}") }

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
                    curve = anchored, physioCurve = physioCurve, incrementalCurve = incCurve,
                    historyData = records.map { it.timestamp to it.value }, modelLabel = modelLabel, predictionTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                    confidence = confidence,
                    totalRecords = totalRecords, fastingBaseline = olParams.fastingBaseline, variability = olParams.glucoseVariability,
                    activeInsulin = iob, todayCarbs = todayCarbs, tcnWeight = tcnW, physioWeight = 1 - tcnW,
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
}
