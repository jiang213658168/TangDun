package com.tangdun.app.ui.prediction

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.GlucoseDao
import com.tangdun.app.data.local.dao.InsulinDao
import com.tangdun.app.data.local.dao.MealDao
import com.tangdun.app.data.local.dao.ExerciseDao
import com.tangdun.app.domain.algorithm.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class PredictionUiState(
    val isLoading: Boolean = true,
    val currentGlucose: Double? = null,
    val predicted30min: Double? = null,
    val predicted60min: Double? = null,
    val predicted120min: Double? = null,
    val riskLevel: String = "normal",
    val curve: List<Double> = emptyList(),
    val tcnCurve: List<Double> = emptyList(),
    val bergmanCurve: List<Double> = emptyList(),
    val tcnWeight: Double = 0.0,
    val bergmanWeight: Double = 0.0,
    val modelType: String = "",
    val predictionTime: String = "",
    val confidence: Double = 0.0,
    val dataDays: Double = 0.0,
    val totalRecords: Int = 0,
    val fastingBaseline: Double = 0.0,
    val variability: Double = 0.0,
    val peakValue: Double = 0.0,
    val peakMinute: Int = 0,
    val error: String? = null
)

@HiltViewModel
class PredictionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glucoseDao: GlucoseDao,
    private val insulinDao: InsulinDao,
    private val mealDao: MealDao,
    private val exerciseDao: ExerciseDao
) : ViewModel() {

    companion object { private const val TAG = "PredictionVM" }

    private val _uiState = MutableStateFlow(PredictionUiState())
    val uiState: StateFlow<PredictionUiState> = _uiState.asStateFlow()

    // ── 你的三模型体系 ──
    // PersonalizedPredictor → FusionPredictor → TCN(15维特征,ONNX) + Bergman(RK4 ODE)
    //                       → OnlineLearner(个性化校正,10000条历史)
    private val predictor = PersonalizedPredictor(context)
    private val onlineLearner = OnlineLearner(context)

    // TCN 模型加载状态
    private val tcnLoaded = predictor.initialize()

    init {
        Log.i(TAG, "初始化: TCN模型=${if (tcnLoaded) "已加载(MAE 0.552)" else "未加载(Bergman兜底)"}")
        loadPrediction()
        // 新数据到达自动更新
        viewModelScope.launch {
            glucoseDao.getLatestFlow()
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.timestamp == new.timestamp }
                .collect { loadPrediction() }
        }
    }

    fun refresh() { loadPrediction() }

    private fun loadPrediction() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // 取288点（24h）并反转为时间正序
                val records = glucoseDao.getRecent(288).reversed()
                if (records.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "暂无血糖数据，等待xDrip+推送...")
                    return@launch
                }
                if (records.size < 10) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "数据不足(需要≥10条)，请等待更多数据...")
                    return@launch
                }

                val currentGlucose = records.last().value
                val glucoseHistory = records.map { it.value }.toDoubleArray()

                // ── 构建288点胰岛素/碳水历史（与训练数据格式一致）──
                val bolusHistory = DoubleArray(288) { 0.0 }
                val recentInsulin = insulinDao.getSince(System.currentTimeMillis() - 24 * 3600 * 1000)
                for (r in recentInsulin) {
                    val idx = (288 - 1) - ((System.currentTimeMillis() - r.timestamp) / 300000).toInt()
                    if (idx in 0 until 288) bolusHistory[idx] = r.doseUnits
                }

                val carbHistory = DoubleArray(288) { 0.0 }
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                for (m in mealDao.getTodayRecords(todayStart)) {
                    val idx = (288 - 1) - ((System.currentTimeMillis() - m.timestamp) / 300000).toInt()
                    if (idx in 0 until 288) carbHistory[idx] = m.totalCarbs
                }

                // ── 核心预测：15维特征全部传入 ──
                val result = predictor.predict(
                    glucoseHistory = glucoseHistory,
                    currentGlucose = currentGlucose,
                    bolusHistory = bolusHistory,
                    carbHistory = carbHistory,
                    heartRateHistory = DoubleArray(288) { 0.0 },  // 无华为手表数据，填0
                    stepHistory = DoubleArray(288) { 0.0 }         // 无华为手表数据，填0
                )

                // ── 学习状态 ──
                val params = onlineLearner.getPersonalParams()
                val stage = onlineLearner.getLearningStage()
                val dataDays = params.dataDays
                val modelName = if (tcnLoaded) {
                    "${result.modelType} (MAE 0.552)"
                } else {
                    "Bergman+个性化 (TCN未加载)"
                }

                val peakIdx = result.curve.indices.maxByOrNull { result.curve[it] } ?: 0
                val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                _uiState.value = PredictionUiState(
                    isLoading = false, currentGlucose = currentGlucose,
                    predicted30min = result.predicted30min, predicted60min = result.predicted60min,
                    predicted120min = result.predicted120min,
                    curve = result.curve, tcnCurve = result.tcnCurve, bergmanCurve = result.bergmanCurve,
                    tcnWeight = result.tcnWeight, bergmanWeight = result.bergmanWeight,
                    modelType = modelName, predictionTime = t,
                    confidence = minOf(100.0, stage.ordinal * 30.0 + minOf(dataDays * 5, 40.0)),
                    dataDays = dataDays, totalRecords = records.size,
                    fastingBaseline = params.fastingBaseline, variability = params.glucoseVariability,
                    riskLevel = when {
                        result.predicted30min != null && result.predicted30min < 3.9 -> "low_risk"
                        result.predicted30min != null && result.predicted30min > 10.0 -> "high_risk"
                        else -> "normal"
                    },
                    peakValue = result.curve[peakIdx], peakMinute = peakIdx * 5,
                    error = null
                )
                Log.i(TAG, "预测更新: 当前${String.format("%.1f", currentGlucose)} TCN_w=${String.format("%.0f%%", result.tcnWeight*100)} 模型=$modelName")
            } catch (e: Exception) {
                Log.e(TAG, "预测失败: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = "预测失败: ${e.message}")
            }
        }
    }

    fun triggerLearning() {
        viewModelScope.launch { onlineLearner.learn(glucoseDao) }
    }

    override fun onCleared() { super.onCleared(); predictor.close() }
}
