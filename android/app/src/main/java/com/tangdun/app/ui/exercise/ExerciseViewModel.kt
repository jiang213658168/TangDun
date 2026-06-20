package com.tangdun.app.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.ExerciseDao
import com.tangdun.app.data.local.dao.GlucoseDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class ExerciseUiState(
    val isLoading: Boolean = true,
    val todayDuration: Int = 0,
    val todaySteps: Int = 0,
    val todayCalories: Double = 0.0,
    val todayExerciseCount: Int = 0,
    val recommendedType: String = "步行",
    val recommendedDuration: Int = 30,
    val recommendedIntensity: String = "中等",
    val expectedGlucoseDrop: Double = 0.5,
    val prescriptionNotes: String = "",
    val error: String? = null,
    val selectedDate: Long = System.currentTimeMillis(),  // ★ v3.0.7
    val records: List<com.tangdun.app.data.local.entity.ExerciseRecord> = emptyList()  // ★ v3.0.7
)

@HiltViewModel
class ExerciseViewModel @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val glucoseDao: GlucoseDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExerciseUiState())
    val uiState: StateFlow<ExerciseUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun refresh() {
        loadData()
    }

    /** ★ v3.0.7: 切换到指定日期的记录 */
    fun goToDate(dateMillis: Long) {
        _uiState.value = _uiState.value.copy(selectedDate = dateMillis)
        loadData()
    }

    fun goToToday() = goToDate(System.currentTimeMillis())

    fun shiftDate(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = _uiState.value.selectedDate; add(Calendar.DAY_OF_MONTH, delta) }
        if (cal.timeInMillis > System.currentTimeMillis()) return
        goToDate(cal.timeInMillis)
    }

    /**
     * ★ 手动添加运动记录
     */
    fun addExercise(
        exerciseType: String,
        durationMin: Int,
        intensity: String,
        steps: Int = 0,
        caloriesBurned: Double = 0.0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            try {
                val record = com.tangdun.app.data.local.entity.ExerciseRecord(
                    startTime = timestamp - durationMin * 60_000L,
                    exerciseType = exerciseType,
                    durationMin = durationMin,
                    intensity = intensity,
                    steps = steps,
                    caloriesBurned = caloriesBurned
                )
                exerciseDao.insert(record)
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteExercise(record: com.tangdun.app.data.local.entity.ExerciseRecord) {
        viewModelScope.launch {
            try {
                exerciseDao.delete(record)
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // ★ v3.0.7: 按 selectedDate 当天 [00:00, 24:00) 过滤
                val cal = Calendar.getInstance().apply {
                    timeInMillis = _uiState.value.selectedDate
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayStart = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val dayEnd = cal.timeInMillis

                val records = exerciseDao.getByTimeRange(dayStart, dayEnd - 1)

                // 今日统计 = 今日 (用 todayStart 计算)
                val todayCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val todayStart = todayCal.timeInMillis

                val totalDuration = exerciseDao.getTodayTotalDuration(todayStart) ?: 0
                val totalSteps = exerciseDao.getTodayTotalSteps(todayStart) ?: 0
                val totalCalories = exerciseDao.getTodayTotalCalories(todayStart) ?: 0.0
                val exerciseCount = exerciseDao.getTodayExerciseCount(todayStart)

                val latestGlucose = glucoseDao.getLatest()
                val prescription = generatePrescription(latestGlucose?.value)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    todayDuration = totalDuration,
                    todaySteps = totalSteps,
                    todayCalories = totalCalories,
                    todayExerciseCount = exerciseCount,
                    recommendedType = prescription.type,
                    recommendedDuration = prescription.duration,
                    recommendedIntensity = prescription.intensity,
                    expectedGlucoseDrop = prescription.expectedDrop,
                    prescriptionNotes = prescription.notes,
                    records = records
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * 生成运动处方
     *
     * 基于当前血糖水平推荐运动
     */
    private fun generatePrescription(currentGlucose: Double?): ExercisePrescription {
        if (currentGlucose == null) {
            return ExercisePrescription(
                type = "步行",
                duration = 30,
                intensity = "中等",
                expectedDrop = 0.5,
                notes = "建议餐后1小时开始运动"
            )
        }

        return when {
            currentGlucose < 3.9 -> ExercisePrescription(
                type = "暂停运动",
                duration = 0,
                intensity = "无",
                expectedDrop = 0.0,
                notes = "低血糖，请先补充碳水，待血糖恢复后再运动"
            )
            currentGlucose < 5.6 -> ExercisePrescription(
                type = "轻度运动",
                duration = 20,
                intensity = "低",
                expectedDrop = 0.3,
                notes = "血糖偏低，建议轻度运动，注意监测"
            )
            currentGlucose <= 10.0 -> ExercisePrescription(
                type = "步行",
                duration = 30,
                intensity = "中等",
                expectedDrop = 0.5,
                notes = "血糖正常，适合中等强度运动"
            )
            currentGlucose <= 13.9 -> ExercisePrescription(
                type = "快走",
                duration = 40,
                intensity = "中高",
                expectedDrop = 0.8,
                notes = "血糖偏高，建议增加运动量，注意补水"
            )
            else -> ExercisePrescription(
                type = "暂停运动",
                duration = 0,
                intensity = "无",
                expectedDrop = 0.0,
                notes = "血糖过高，请先检测酮体，酮体阳性禁止运动"
            )
        }
    }

    data class ExercisePrescription(
        val type: String,
        val duration: Int,
        val intensity: String,
        val expectedDrop: Double,
        val notes: String
    )
}
