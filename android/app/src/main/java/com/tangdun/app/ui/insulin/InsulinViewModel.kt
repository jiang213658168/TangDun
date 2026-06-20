package com.tangdun.app.ui.insulin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.InsulinDao
import com.tangdun.app.data.local.entity.InsulinRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class InsulinUiState(
    val isLoading: Boolean = true,
    val records: List<InsulinRecord> = emptyList(),
    val todayTotalDose: Double = 0.0,
    val iob: Double = 0.0,  // 活性胰岛素
    val error: String? = null,
    val selectedDate: Long = System.currentTimeMillis()  // ★ v3.0.7: 查看日期
)

@HiltViewModel
class InsulinViewModel @Inject constructor(
    private val insulinDao: InsulinDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsulinUiState())
    val uiState: StateFlow<InsulinUiState> = _uiState.asStateFlow()

    init {
        loadRecords()
    }

    fun refresh() {
        loadRecords()
    }

    /** ★ v3.0.7: 切换到指定日期的记录 */
    fun goToDate(dateMillis: Long) {
        _uiState.value = _uiState.value.copy(selectedDate = dateMillis)
        loadRecords()
    }

    fun goToToday() {
        goToDate(System.currentTimeMillis())
    }

    fun shiftDate(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = _uiState.value.selectedDate; add(Calendar.DAY_OF_MONTH, delta) }
        if (cal.timeInMillis > System.currentTimeMillis()) return
        goToDate(cal.timeInMillis)
    }

    fun addInsulin(
        insulinType: String,
        doseUnits: Double,
        injectionSite: String?,
        notes: String?,
        timestamp: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            try {
                val record = InsulinRecord(
                    timestamp = timestamp,
                    insulinType = insulinType,
                    doseUnits = doseUnits,
                    injectionSite = injectionSite,
                    notes = notes
                )
                insulinDao.insert(record)

                // ★ 通知自学习引擎: 已记录胰岛素
                com.tangdun.app.domain.algorithm.SelfLearningManager.notifyInsulinRecorded()

                loadRecords()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteRecord(record: InsulinRecord) {
        viewModelScope.launch {
            try {
                insulinDao.delete(record)
                // ★ 通知自学习: 胰岛素已删除 → 重新检查数据完整度
                com.tangdun.app.domain.algorithm.SelfLearningManager.notifyInsulinDeleted()
                loadRecords()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun editDose(record: InsulinRecord, newDose: Double) {
        viewModelScope.launch {
            try {
                insulinDao.update(record.copy(doseUnits = newDose))
                loadRecords()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * ★ 修复跨日编辑: 支持编辑胰岛素记录的时间戳
     * 场景: 用户 23:59 打胰岛素, 进入第二天编辑时想选"昨天"的时间
     */
    fun editTimestamp(record: InsulinRecord, newTimestamp: Long) {
        viewModelScope.launch {
            try {
                insulinDao.update(record.copy(timestamp = newTimestamp))
                loadRecords()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun loadRecords() {
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

                val records = insulinDao.getByTimeRange(dayStart, dayEnd - 1)  // 包含当天 23:59:59.999
                // 今日剂量 + IOB 始终用今天的 (因为 iob 是活性, 跟历史日期无关)
                val todayCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val todayDose = insulinDao.getTodayTotalDose(todayCal.timeInMillis) ?: 0.0
                val recentRecords = insulinDao.getSince(
                    System.currentTimeMillis() - 4 * 3600 * 1000
                )
                val iob = InsulinRecord.calculateIOB(recentRecords, System.currentTimeMillis())

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    records = records,
                    todayTotalDose = todayDose,
                    iob = iob
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
