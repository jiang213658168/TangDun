package com.tangdun.app.sync

import android.content.Context
import android.util.Log
import com.tangdun.app.data.local.dao.GlucoseDao
import com.tangdun.app.data.local.entity.GlucoseRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * 血糖数据源管理器
 *
 * 统一管理所有血糖数据来源：
 * 1. Companion App（欧泰健康等CGM App分享）
 * 2. xDrip+ Content Provider
 * 3. xDrip+ REST API
 * 4. 手动输入
 *
 * 自动选择最佳数据源
 */
class GlucoseDataSource(
    private val context: Context,
    private val glucoseDao: GlucoseDao
) {
    companion object {
        private const val TAG = "GlucoseDataSource"
    }

    private val companionReceiver = CompanionAppReceiver(context)
    private val xDripManager = XDripManager(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 数据源状态
    data class SourceStatus(
        val companionActive: Boolean = false,
        val xdripActive: Boolean = false,
        val lastReading: GlucoseRecord? = null,
        val source: String = "none"
    )

    private val _status = MutableStateFlow(SourceStatus())
    val status: StateFlow<SourceStatus> = _status

    /**
     * 启动数据源
     */
    fun start() {
        // 启动Companion App接收器
        companionReceiver.startListening()

        // 监听Companion App数据
        scope.launch {
            companionReceiver.latestReading.collectLatest { reading ->
                if (reading != null) {
                    saveToDatabase(reading.valueMmol, reading.timestamp, "companion")
                    _status.value = _status.value.copy(
                        companionActive = true,
                        source = "companion"
                    )
                }
            }
        }

        // 定期检查xDrip+
        scope.launch {
            while (isActive) {
                checkXDrip()
                delay(5 * 60 * 1000) // 每5分钟检查一次
            }
        }

        Log.d(TAG, "血糖数据源已启动")
    }

    /**
     * 停止数据源
     */
    fun stop() {
        companionReceiver.stopListening()
        scope.cancel()
    }

    /**
     * 检查xDrip+数据
     */
    private suspend fun checkXDrip() {
        try {
            val readings = xDripManager.getGlucoseHistory(hours = 1)
            if (readings.isNotEmpty()) {
                for (reading in readings) {
                    saveToDatabase(reading.valueMmol, reading.timestamp, "xdrip")
                }
                _status.value = _status.value.copy(xdripActive = true)
            }
        } catch (e: Exception) {
            // xDrip+不可用
            _status.value = _status.value.copy(xdripActive = false)
        }
    }

    /**
     * 保存血糖数据到数据库
     */
    private suspend fun saveToDatabase(glucoseMmol: Double, timestamp: Long, source: String) {
        try {
            // 检查是否已存在
            val latest = glucoseDao.getLatest()
            if (latest != null && latest.timestamp >= timestamp) {
                return // 跳过旧数据
            }

            val record = GlucoseRecord(
                timestamp = timestamp,
                value = glucoseMmol,
                source = source,
                trend = null
            )
            glucoseDao.insert(record)
            Log.d(TAG, "保存血糖: $glucoseMmol mmol/L (来源: $source)")
        } catch (e: Exception) {
            Log.e(TAG, "保存血糖失败: ${e.message}")
        }
    }

    /**
     * 手动添加血糖
     */
    suspend fun addManualGlucose(value: Double, scene: String = "other") {
        val timestamp = System.currentTimeMillis()
        saveToDatabase(value, timestamp, "manual")
    }

    /**
     * 获取数据源状态
     */
    fun getStatus(): SourceStatus {
        return _status.value
    }
}
