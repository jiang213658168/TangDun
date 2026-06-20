package com.tangdun.app.ui.meal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.MealDao
import com.tangdun.app.data.local.entity.MealRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class MealUiState(
    val isLoading: Boolean = true,
    val meals: List<MealRecord> = emptyList(),
    val todayCarbs: Double = 0.0,
    val todayCalories: Double = 0.0,
    val error: String? = null,
    val selectedDate: Long = System.currentTimeMillis()  // ★ v3.0.7: 查看日期
)

@HiltViewModel
class MealViewModel @Inject constructor(
    private val mealDao: MealDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealUiState())
    val uiState: StateFlow<MealUiState> = _uiState.asStateFlow()

    init {
        loadMeals()
    }

    fun refresh() {
        loadMeals()
    }

    /** ★ v3.0.7: 切换到指定日期的记录 */
    fun goToDate(dateMillis: Long) {
        _uiState.value = _uiState.value.copy(selectedDate = dateMillis)
        loadMeals()
    }

    fun goToToday() = goToDate(System.currentTimeMillis())

    fun shiftDate(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = _uiState.value.selectedDate; add(Calendar.DAY_OF_MONTH, delta) }
        if (cal.timeInMillis > System.currentTimeMillis()) return
        goToDate(cal.timeInMillis)
    }

    fun addMeal(mealType: String, foodName: String, carbs: Double, calories: Double, gi: Double,
                protein: Double = 0.0, fat: Double = 0.0, fiber: Double = 0.0,
                portionGrams: Double = 100.0, timestamp: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            try {
                // 创建饮食记录
                val record = MealRecord(
                    timestamp = timestamp,
                    mealType = mealType,
                    totalCarbs = carbs,
                    totalCalories = calories,
                    totalProtein = protein,
                    totalFat = fat,
                    totalFiber = fiber,
                    avgGi = gi
                )
                val mealId = mealDao.insert(record)

                // 创建饮食明细
                val item = com.tangdun.app.data.local.entity.MealItem(
                    mealId = mealId,
                    foodName = foodName,
                    portionGrams = portionGrams,
                    carbs = carbs,
                    calories = calories,
                    protein = protein,
                    fat = fat,
                    fiber = fiber,
                    gi = gi
                )
                mealDao.insertItem(item)

                // ★ 通知自学习引擎: 已记录饮食
                com.tangdun.app.domain.algorithm.SelfLearningManager.notifyMealRecorded()

                // 刷新数据
                loadMeals()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * 删除饮食记录
     */
    fun deleteMeal(record: MealRecord) {
        viewModelScope.launch {
            try {
                mealDao.deleteWithItems(record)
                // ★ 通知自学习: 饮食已删除 → 重新检查数据完整度
                com.tangdun.app.domain.algorithm.SelfLearningManager.notifyMealDeleted()
                loadMeals()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun loadMeals() {
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

                val meals = mealDao.getByTimeRange(dayStart, dayEnd - 1)

                // 今日统计用今天 (历史日期的碳水/热量当卡片显示)
                val todayCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val todayStart = todayCal.timeInMillis
                val totalCarbs = mealDao.getTodayTotalCarbs(todayStart) ?: 0.0
                val totalCalories = mealDao.getTodayTotalCalories(todayStart) ?: 0.0

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    meals = meals,
                    todayCarbs = totalCarbs,
                    todayCalories = totalCalories
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
}
