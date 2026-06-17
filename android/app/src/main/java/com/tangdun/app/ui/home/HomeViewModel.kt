package com.tangdun.app.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tangdun.app.data.local.dao.AlertDao
import com.tangdun.app.data.local.dao.GlucoseDao
import com.tangdun.app.data.local.entity.AlertRecord
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.data.local.dao.InsulinDao
import com.tangdun.app.domain.algorithm.AlertEngine
import com.tangdun.app.domain.algorithm.CGMPreprocessor
import com.tangdun.app.domain.algorithm.SmartAdvisor
import com.tangdun.app.domain.algorithm.TrendCalculator
import com.tangdun.app.sync.XDripManager
import com.tangdun.app.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val currentGlucose: Double? = null,
    val trend: String? = null,
    val change30min: Double? = null,
    val glucoseData: List<Pair<Long, Double>> = emptyList(),  // (timestamp_ms, glucose_mmol)
    val records: List<GlucoseRecord> = emptyList(),
    val alerts: List<AlertRecord> = emptyList(),
    val advices: List<SmartAdvisor.Advice> = emptyList(),
    val avgGlucose: Double? = null,
    val tir: Double? = null,
    val recordCount: Int = 0,
    val isXDripConnected: Boolean = false,
    val selectedDate: Long = System.currentTimeMillis(),  // 查看的日期(毫秒)
    val targetLow: Float = 3.9f,
    val targetHigh: Float = 10.0f,
    val error: String? = null,
    // 指尖校准
    val calOffset: Double = 0.0,
    val calCount: Int = 0,
    val calConfidence: String = "",
    val lastCalTime: Long = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glucoseDao: GlucoseDao,
    private val alertDao: AlertDao,
    private val insulinDao: InsulinDao,
    private val settingsManager: SettingsManager,
    private val cgmPreprocessor: CGMPreprocessor,
    private val alertEngine: AlertEngine,
    private val smartAdvisor: SmartAdvisor,
    private val trendCalculator: TrendCalculator
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val xDripManager = XDripManager(context)
    private val cgmCalibrator = com.tangdun.app.domain.algorithm.CGMCalibrator(context)
    private var lastHomeLearnTime = 0L

    init {
        loadData()
        checkXDripStatus()
        startXDripSync()

        // 监听新血糖数据 → 刷新UI (自学习由SelfLearningManager统一管理)
        viewModelScope.launch {
            glucoseDao.getLatestFlow()
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.timestamp == new.timestamp }
                .debounce(2000)
                .collect { loadData() }
        }

        // 监听设置变化（目标范围改变时自动刷新）
        viewModelScope.launch {
            combine(settingsManager.targetLow, settingsManager.targetHigh) { low, high ->
                Pair(low, high)
            }.distinctUntilChanged().collect { (low, high) ->
                _uiState.value = _uiState.value.copy(targetLow = low, targetHigh = high)
                loadData()
            }
        }
    }

    fun refresh() { loadData(_uiState.value.selectedDate); checkXDripStatus() }
    fun goToToday() { val d = System.currentTimeMillis(); _uiState.value = _uiState.value.copy(selectedDate = d); loadData(d) }
    fun shiftDate(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = _uiState.value.selectedDate; add(Calendar.DAY_OF_MONTH, delta) }
        if (cal.timeInMillis > System.currentTimeMillis()) return
        val d = cal.timeInMillis
        _uiState.value = _uiState.value.copy(selectedDate = d)
        loadData(d)  // ★ 传参防竞态: 不读可变状态
    }

    /** 导入欧态CGM xlsx文件 */
    fun importXlsx(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(error = "导入中...")
                val result = com.tangdun.app.service.XlsxImporter.importFromUri(context, uri)

                if (result.imported > 0) {
                    loadData()
                    // 触发统计学习
                    try {
                        val learner = com.tangdun.app.domain.algorithm.SelfLearningManager.getOnlineLearner()
                        learner.learn(glucoseDao)
                        Log.i("HomeVM", "导入后统计学习: ${learner.getStageDescription()}")
                    } catch (e: Exception) { Log.w("HomeVM", "导入后学习失败: ${e.message}") }

                    // ★ EDOC批量处理: 对导入数据逐条模拟即时纠错
                    _uiState.value = _uiState.value.copy(error = "即时纠错中...")
                    try {
                        val edoc = com.tangdun.app.domain.algorithm.SelfLearningManager.getEDOCCorrector()
                        val importedRecords = glucoseDao.getRecent(result.imported.toInt()).reversed()
                        val baseParams = com.tangdun.app.domain.algorithm.SelfLearningManager.getBaseParams()
                        if (importedRecords.isNotEmpty() && baseParams != null) {
                            val batchCorrections = edoc.processBatchImport(
                                importedRecords, baseParams
                            ) { processed, total, corrections ->
                                // 进度更新到UI
                                _uiState.value = _uiState.value.copy(
                                    error = "即时纠错: $processed/$total ($corrections 次修正)"
                                )
                            }
                            Log.i("HomeVM", "EDOC批量处理完成: ${importedRecords.size}条 → $batchCorrections 次修正")
                        }
                    } catch (e: Exception) { Log.w("HomeVM", "EDOC批量处理失败: ${e.message}") }
                }
                _uiState.value = _uiState.value.copy(
                    error = "导入完成: ${result.imported}条新增, ${result.skipped}条重复"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "导入失败: ${e.message}")
            }
        }
    }

    /** 指尖血校准: 比较指尖值与最新CGM，更新偏移 */
    fun calibrateNow(fingerValue: Double) {
        viewModelScope.launch {
            try {
                val latestCgm = glucoseDao.getLatest()
                if (latestCgm != null && latestCgm.source != "finger") {
                    val result = cgmCalibrator.calibrate(fingerValue, latestCgm.value)
                    Log.i("HomeVM", "校准: finger=$fingerValue cgm=${latestCgm.value} → offset=${"%.2f".format(result.offset)} conf=${result.confidence}")
                    // 同时保存指尖记录
                    glucoseDao.insert(GlucoseRecord(timestamp = System.currentTimeMillis(), value = fingerValue, source = "finger", scene = "other"))
                    loadData()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "校准失败: ${e.message}")
            }
        }
    }

    /**
     * 手动添加血糖记录
     */
    fun addGlucose(value: Double, source: String, timestamp: Long = System.currentTimeMillis(), scene: String = "other") {
        viewModelScope.launch {
            try {
                // 计算趋势
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val existing = glucoseDao.getTodayRecords(cal.timeInMillis)
                val trend = if (existing.size >= 3) trendCalculator.calculateTrend(existing).arrow else null

                val record = GlucoseRecord(
                    timestamp = timestamp,
                    value = value,
                    source = source,
                    trend = trend,
                    scene = scene
                )
                // ★ 指尖血触发校准: 插入前先获取最新CGM值，比较后更新校准偏移
                if (source == "finger") {
                    val latestBeforeInsert = glucoseDao.getLatest()
                    if (latestBeforeInsert != null && latestBeforeInsert.source != "finger") {
                        val result = cgmCalibrator.calibrate(value, latestBeforeInsert.value)
                        Log.i("HomeVM", "指尖校准: finger=${value} cgm=${latestBeforeInsert.value} → offset=${"%.2f".format(result.offset)} ${result.confidence}")
                    }
                }
                glucoseDao.insert(record)

                // 检查预警
                checkAlerts(value)

                // 刷新数据
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * 删除血糖记录
     */
    fun deleteGlucose(record: GlucoseRecord) {
        viewModelScope.launch {
            try {
                glucoseDao.delete(record)
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * 编辑血糖记录
     */
    fun editGlucose(record: GlucoseRecord, newValue: Double) {
        viewModelScope.launch {
            try {
                glucoseDao.update(record.copy(value = newValue))
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun loadData(dateMillis: Long = _uiState.value.selectedDate) {
        val capturedDate = dateMillis  // ★ 捕获参数防竞态
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val cal = Calendar.getInstance().apply { timeInMillis = capturedDate; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                val dayStart = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH, 1); val dayEnd = cal.timeInMillis

                // 获取选中日期的血糖记录
                val records = glucoseDao.getByTimeRange(dayStart, dayEnd)
                val isToday = dayStart <= System.currentTimeMillis() && System.currentTimeMillis() < dayEnd

                // 今天→DB最新值; 历史日期→当天最后一条
                val latest = if (isToday) glucoseDao.getLatest() else records.lastOrNull()

                // 趋势：优先用 xDrip+ 广播自带的（更准确），其次本地计算
                val trend = latest?.trend
                    ?: if (records.size >= 4) trendCalculator.calculateTrend(records).arrow
                    else null

                // 计算30分钟变化
                val change30min = if (records.size >= 6) {
                    val current = records.last().value
                    val prev = records[records.size - 6].value
                    current - prev
                } else null

                // 构建图表数据（时间戳，血糖值）
                val glucoseData = records.map { record ->
                    Pair(record.timestamp, record.value)
                }

                // 计算统计
                val avgGlucose = if (records.isNotEmpty()) {
                    records.map { it.value }.average()
                } else null

                val low = _uiState.value.targetLow.toDouble()
                val high = _uiState.value.targetHigh.toDouble()
                val tir = if (records.isNotEmpty()) {
                    val inRange = records.count { it.value in low..high }
                    inRange.toDouble() / records.size * 100
                } else null

                // 获取未读预警
                val alerts = alertDao.getUnread()

                // 获取最近胰岛素记录
                val recentInsulin = insulinDao.getSince(
                    System.currentTimeMillis() - 4 * 3600 * 1000  // 最近4小时
                )

                // 计算智能建议
                val advices = if (latest != null) {
                    smartAdvisor.analyze(
                        currentGlucose = latest.value,
                        trend = trend,
                        recentReadings = records,
                        recentInsulin = recentInsulin,
                        targetLow = low,
                        targetHigh = high,
                        severeLow = settingsManager.getSevereLow().toDouble(),
                        severeHigh = settingsManager.getSevereHigh().toDouble(),
                        insulinSensitivity = settingsManager.getInsulinSensitivity().toDouble(),
                        carbRatio = settingsManager.getCarbRatio().toDouble()
                    )
                } else {
                    emptyList()
                }

                // 有数据就算连上了
                val connected = _uiState.value.isXDripConnected || records.isNotEmpty()
                val prev = _uiState.value

                _uiState.value = prev.copy(
                    isLoading = false,
                    currentGlucose = latest?.value,
                    trend = trend, change30min = change30min,
                    glucoseData = glucoseData, records = records,
                    alerts = alerts, advices = advices,
                    avgGlucose = avgGlucose, tir = tir,
                    recordCount = records.size, isXDripConnected = connected,
                    calOffset = cgmCalibrator.getOffset(),
                    calCount = cgmCalibrator.getCount(),
                    calConfidence = if (cgmCalibrator.getCount() >= 1) "已校准${cgmCalibrator.getCount()}次" else "未校准",
                    lastCalTime = System.currentTimeMillis()
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
     * 检查xDrip+是否可用
     */
    private fun checkXDripStatus() {
        viewModelScope.launch {
            try {
                val isAvailable = xDripManager.isXDripAvailable()
                _uiState.value = _uiState.value.copy(isXDripConnected = isAvailable)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isXDripConnected = false)
            }
        }
    }

    /**
     * 启动xDrip+数据同步
     */
    fun syncHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = "正在同步xDrip+历史数据...")
            try {
                // 用REST API（需要xDrip+开启Web Server）
                val readings = xDripManager.getGlucoseHistory(hours = 24 * 7)
                if (readings.isEmpty()) {
                    _uiState.value = _uiState.value.copy(error = "请开启xDrip+ Web Server:\n设置→Less common settings→Enable Web Server→端口17580")
                    return@launch
                }
                val existing = glucoseDao.getByTimeRange(readings.first().timestamp, readings.last().timestamp).map { it.timestamp }.toSet()
                val newRecords = readings.filter { it.timestamp !in existing }.map { r ->
                    GlucoseRecord(timestamp = r.timestamp, value = r.valueMmol, source = "xdrip", trend = r.trend)
                }
                if (newRecords.isNotEmpty()) glucoseDao.insertAll(newRecords)
                _uiState.value = _uiState.value.copy(isXDripConnected = true, error = if (newRecords.isNotEmpty()) "已同步${newRecords.size}条数据" else "数据已最新")
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "同步失败: ${e.message}")
            }
        }
    }

    private fun startXDripSync() {
        viewModelScope.launch {
            syncHistory()
        }
    }

    /**
     * 检查预警
     */
    private suspend fun checkAlerts(currentValue: Double) {
        try {
            val alerts = alertEngine.checkAll(
                currentValue = currentValue,
                trend = _uiState.value.trend,
                predicted30min = null,
                recentROC = null
            )

            for (alert in alerts) {
                alertDao.insert(alert)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }
}
