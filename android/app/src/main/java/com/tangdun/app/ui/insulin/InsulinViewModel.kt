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
    val error: String? = null
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

    private fun loadRecords() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = calendar.timeInMillis

                val records = insulinDao.getRecent(50)
                val todayDose = insulinDao.getTodayTotalDose(todayStart) ?: 0.0

                // 计算IOB（活性胰岛素）
                val recentRecords = insulinDao.getSince(
                    System.currentTimeMillis() - 4 * 3600 * 1000
                )
                val iob = InsulinRecord.calculateIOB(recentRecords, System.currentTimeMillis())

                _uiState.value = InsulinUiState(
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
