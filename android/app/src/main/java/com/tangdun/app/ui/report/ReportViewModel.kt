package com.tangdun.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.*
import com.tangdun.app.domain.algorithm.ReportGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class ReportUiState(
    val isLoading: Boolean = true,
    val dailyReport: ReportGenerator.DailyReport? = null,
    val weeklyReport: ReportGenerator.WeeklyReport? = null,
    val monthlyReport: ReportGenerator.MonthlyReport? = null,
    val error: String? = null
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val glucoseDao: GlucoseDao,
    private val mealDao: MealDao,
    private val insulinDao: InsulinDao,
    private val exerciseDao: ExerciseDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    private val reportGenerator = ReportGenerator()

    init {
        loadReports()
    }

    fun refresh() {
        loadReports()
    }

    private fun loadReports() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val now = System.currentTimeMillis()
                val calendar = Calendar.getInstance()

                // 今日数据
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val todayStart = calendar.timeInMillis

                val todayGlucose = glucoseDao.getTodayRecords(todayStart)
                val todayMeals = mealDao.getTodayRecords(todayStart)
                val todayInsulin = insulinDao.getSince(todayStart)
                val todayExercise = exerciseDao.getTodayRecords(todayStart)

                // 日报告
                val dailyReport = reportGenerator.generateDailyReport(
                    date = todayStart,
                    glucoseRecords = todayGlucose,
                    mealRecords = todayMeals,
                    insulinRecords = todayInsulin,
                    exerciseRecords = todayExercise
                )

                // 周报告（简化：使用今日数据）
                val weeklyReport = reportGenerator.generateWeeklyReport(
                    startDate = todayStart - 7 * 24 * 3600 * 1000,
                    endDate = todayStart,
                    dailyReports = listOf(dailyReport)
                )

                // 月报告
                val month = calendar.get(Calendar.MONTH) + 1
                val year = calendar.get(Calendar.YEAR)
                val monthlyReport = reportGenerator.generateMonthlyReport(
                    year = year,
                    month = month,
                    dailyReports = listOf(dailyReport)
                )

                _uiState.value = ReportUiState(
                    isLoading = false,
                    dailyReport = dailyReport,
                    weeklyReport = weeklyReport,
                    monthlyReport = monthlyReport
                )
            } catch (e: Exception) {
                _uiState.value = ReportUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
}
