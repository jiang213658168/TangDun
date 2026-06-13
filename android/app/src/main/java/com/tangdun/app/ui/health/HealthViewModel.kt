package com.tangdun.app.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.*
import com.tangdun.app.data.local.entity.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class HealthUiState(
    val isLoading: Boolean = true,
    val sleepRecords: List<SleepRecord> = emptyList(),
    val bpRecords: List<BloodPressureRecord> = emptyList(),
    val weightRecords: List<WeightRecord> = emptyList(),
    val ketoneRecords: List<KetoneRecord> = emptyList(),
    val medicationRecords: List<MedicationRecord> = emptyList(),
    val symptomRecords: List<SymptomRecord> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val sleepDao: SleepDao,
    private val bloodPressureDao: BloodPressureDao,
    private val weightDao: WeightDao,
    private val ketoneDao: KetoneDao,
    private val medicationDao: MedicationDao,
    private val symptomDao: SymptomDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        loadAllRecords()
    }

    fun refresh() {
        loadAllRecords()
    }

    fun addRecord(type: String, data: Map<String, Any>) {
        viewModelScope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                when (type) {
                    "sleep" -> {
                        val duration = (data["duration"] as? Number)?.toInt() ?: 480
                        sleepDao.insert(SleepRecord(
                            timestamp = timestamp,
                            sleepTime = timestamp - duration * 60000,
                            wakeTime = timestamp,
                            durationMinutes = duration
                        ))
                    }
                    "blood_pressure" -> {
                        val systolic = (data["systolic"] as? Number)?.toInt() ?: 120
                        val diastolic = (data["diastolic"] as? Number)?.toInt() ?: 80
                        bloodPressureDao.insert(BloodPressureRecord(
                            timestamp = timestamp,
                            systolic = systolic,
                            diastolic = diastolic
                        ))
                    }
                    "weight" -> {
                        val weight = (data["weight"] as? Number)?.toDouble() ?: 70.0
                        weightDao.insert(WeightRecord(
                            timestamp = timestamp,
                            weightKg = weight
                        ))
                    }
                    "ketone" -> {
                        val level = (data["level"] as? Number)?.toDouble() ?: 0.0
                        ketoneDao.insert(KetoneRecord(
                            timestamp = timestamp,
                            ketoneLevel = level
                        ))
                    }
                    "medication" -> {
                        val name = data["name"] as? String ?: ""
                        val dose = data["dose"] as? String ?: ""
                        medicationDao.insert(MedicationRecord(
                            timestamp = timestamp,
                            medicationName = name,
                            dose = dose
                        ))
                    }
                    "symptom" -> {
                        val symptomType = data["type"] as? String ?: "other"
                        val symptoms = data["symptoms"] as? String ?: ""
                        val glucose = data["glucose"] as? Double
                        symptomDao.insert(SymptomRecord(
                            timestamp = timestamp,
                            symptomType = symptomType,
                            symptoms = symptoms,
                            glucoseValue = glucose
                        ))
                    }
                }
                loadAllRecords()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * 删除记录
     */
    fun deleteRecord(type: String, id: Long) {
        viewModelScope.launch {
            try {
                when (type) {
                    "sleep" -> {
                        val record = sleepDao.getRecent(100).find { it.id == id }
                        if (record != null) sleepDao.delete(record)
                    }
                    "bp" -> {
                        val record = bloodPressureDao.getRecent(100).find { it.id == id }
                        if (record != null) bloodPressureDao.delete(record)
                    }
                    "weight" -> {
                        val record = weightDao.getRecent(100).find { it.id == id }
                        if (record != null) weightDao.delete(record)
                    }
                    "ketone" -> {
                        val record = ketoneDao.getRecent(100).find { it.id == id }
                        if (record != null) ketoneDao.delete(record)
                    }
                    "medication" -> {
                        val record = medicationDao.getRecent(100).find { it.id == id }
                        if (record != null) medicationDao.delete(record)
                    }
                    "symptom" -> {
                        val record = symptomDao.getRecent(100).find { it.id == id }
                        if (record != null) symptomDao.delete(record)
                    }
                }
                loadAllRecords()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun loadAllRecords() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                _uiState.value = HealthUiState(
                    isLoading = false,
                    sleepRecords = sleepDao.getRecent(30),
                    bpRecords = bloodPressureDao.getRecent(30),
                    weightRecords = weightDao.getRecent(30),
                    ketoneRecords = ketoneDao.getRecent(30),
                    medicationRecords = medicationDao.getRecent(30),
                    symptomRecords = symptomDao.getRecent(30)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
