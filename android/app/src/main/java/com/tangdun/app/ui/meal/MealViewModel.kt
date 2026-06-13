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
    val error: String? = null
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

    fun addMeal(mealType: String, foodName: String, carbs: Double, calories: Double, gi: Double, timestamp: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            try {
                // 创建饮食记录
                val record = MealRecord(
                    timestamp = timestamp,
                    mealType = mealType,
                    totalCarbs = carbs,
                    totalCalories = calories,
                    totalProtein = 0.0,
                    totalFat = 0.0,
                    totalFiber = 0.0,
                    avgGi = gi
                )
                val mealId = mealDao.insert(record)

                // 创建饮食明细
                val item = com.tangdun.app.data.local.entity.MealItem(
                    mealId = mealId,
                    foodName = foodName,
                    portionGrams = 100.0,
                    carbs = carbs,
                    calories = calories,
                    protein = 0.0,
                    fat = 0.0,
                    fiber = 0.0,
                    gi = gi
                )
                mealDao.insertItem(item)

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
                // 获取今日开始时间
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = calendar.timeInMillis

                // 获取今日饮食记录
                val meals = mealDao.getTodayRecords(todayStart)

                // 获取今日总碳水和热量
                val totalCarbs = mealDao.getTodayTotalCarbs(todayStart) ?: 0.0
                val totalCalories = mealDao.getTodayTotalCalories(todayStart) ?: 0.0

                // 食物营养信息现由大模型查询，不再使用本地数据库
                _uiState.value = MealUiState(
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
