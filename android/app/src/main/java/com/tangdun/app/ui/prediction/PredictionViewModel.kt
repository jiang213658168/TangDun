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
    val riskLevel: String = "正常", val curve: List<Double> = emptyList(),
    val historyData: List<Pair<Long, Double>> = emptyList(),
    val modelLabel: String = "", val predictionTime: String = "",
    val confidence: Double = 0.0, val totalRecords: Int = 0,
    val fastingBaseline: Double = 0.0, val variability: Double = 0.0,
    val activeInsulin: Double = 0.0, val todayCarbs: Double = 0.0,
    val targetLow: Double = 3.9, val targetHigh: Double = 10.0,
    val tcnWeight: Double = 0.0, val physioWeight: Double = 0.0,
    val peakValue: Double = 0.0, val peakMinute: Int = 0,
    val isfEstimate: Double = 1.5, val crEstimate: Double = 12.0,
    val historyHours: Int = 3,  // 历史窗口(小时)
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
                val est = AutoParamEstimator.estimate(glucoseDao.getRecent(500), insulinDao.getRecent(300), mealDao.getRecent(100))
                if (est.confidence == "高" || est.confidence == "中") {
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
                val dynSigma = (3.0 * 2.0 / isf.coerceIn(0.5, 6.0)).coerceIn(0.5, 6.0)
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
                val finalParams = dmParams.copy(kStomach = adjKStomach)

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
                var merged = personalizedCurve
                if (tcnOk && records.size >= 10) {
                    try {
                        val bh = DoubleArray(288) { 0.0 }; val ch = DoubleArray(288) { 0.0 }
                        for (r in insulin) { val i = (287 - ((now - r.timestamp) / 300000).toInt()); if (i in 0..287) bh[i] += r.doseUnits }
                        for (m in meals24h) { val i = (287 - ((now - m.timestamp) / 300000).toInt()); if (i in 0..287) ch[i] += m.totalCarbs }
                        val r = predictor.predict(gh, g, bh, ch)
                        val nPoints = minOf(r.curve.size, personalizedCurve.size)
                        if (r != null && nPoints >= 25) {
                            // ★ TCN曲线配准: 对齐起点到当前血糖 (消除d参数偏移)
                            val tcnOffset = r.curve[0] - g
                            val alignedTcn = r.curve.map { it - tcnOffset }
                            tcnW = r.tcnWeight
                            merged = (0 until nPoints).map { i -> tcnW * alignedTcn[i] + (1 - tcnW) * personalizedCurve[i] }
                            modelLabel = "TCN+DallaMan(7室 ${"%.0f".format(weight)}kg ${mealInputs.size}餐 ${insulinInputs.size}针)"
                        }
                    } catch (e: Exception) { Log.w(TAG, "TCN异常: ${e.message}") }
                }

                val riskLabel = when {
                    merged.getOrNull(6)?.let { it < settings.getTargetLow().toDouble() } == true -> "低血糖风险"
                    merged.getOrNull(6)?.let { it > settings.getTargetHigh().toDouble() } == true -> "高血糖风险"
                    else -> "正常"
                }

                val peak = merged.max(); val pi = merged.indexOf(peak)
                // 置信度: 综合数据量+模型类型+校准状态+噪声
                val calConf = if (cgmCalibrator.getCount() >= 1) 10.0 else 0.0
                val confidence = when {
                    tcnOk && records.size >= 200 -> 85.0
                    tcnOk && records.size >= 100 -> 75.0
                    records.size >= 50 -> 60.0 + calConf
                    records.size >= 10 -> 45.0 + calConf
                    else -> 30.0
                }

                _uiState.value = PredictionUiState(
                    isLoading = false, currentGlucose = g, riskLevel = riskLabel,
                    predicted30min = merged.getOrNull(6), predicted60min = merged.getOrNull(12), predicted120min = merged.getOrNull(24),
                    curve = merged, historyData = records.map { it.timestamp to it.value }, modelLabel = modelLabel, predictionTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                    confidence = confidence,
                    totalRecords = records.size, fastingBaseline = olParams.fastingBaseline, variability = olParams.glucoseVariability,
                    activeInsulin = iob, todayCarbs = todayCarbs, tcnWeight = tcnW, physioWeight = 1 - tcnW,
                    targetLow = settings.getTargetLow().toDouble(), targetHigh = settings.getTargetHigh().toDouble(),
                    peakValue = peak, peakMinute = pi * 5,
                    isfEstimate = est.insulinSensitivity, crEstimate = est.carbRatio, error = null
                )
                Log.i(TAG, "预测: ${String.format("%.1f", g)} IOB${String.format("%.1f", iob)} 碳水${String.format("%.0f", todayCarbs)} 模型=$modelLabel ISF≈${String.format("%.1f", est.insulinSensitivity)} CR≈${String.format("%.1f", est.carbRatio)}")
            } catch (e: Exception) { Log.e(TAG, "预测失败: ${e.message}", e); _uiState.value = _uiState.value.copy(isLoading = false, error = "预测失败: ${e.message}") }
        }
    }
}
