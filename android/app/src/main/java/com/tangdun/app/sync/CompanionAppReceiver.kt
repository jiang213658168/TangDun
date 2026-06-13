package com.tangdun.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion App数据接收器
 *
 * 接收来自其他CGM应用（如欧泰健康）通过companion方式分享的血糖数据
 *
 * 数据来源：
 * - 欧泰健康 → Companion分享 → 本App
 * - 其他支持companion分享的CGM App
 *
 * 参考xDrip+的companion app数据源实现
 */
class CompanionAppReceiver(private val context: Context) {

    companion object {
        private const val TAG = "CompanionAppReceiver"

        // xDrip+ companion app broadcast action
        const val ACTION_CGM_BG = "com.eveningoutpost.dexdrip.BgEstimate"
        const val ACTION_CGM_BG_FOLLOWER = "com.eveningoutpost.dexdrip.BgReading"

        // 血糖数据键
        const val KEY_BG_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate"
        const val KEY_TIMESTAMP = "com.eveningoutpost.dexdrip.timestamp"
        const val KEY_DEX_TIMESTAMP = "com.dexcom.core.THIN_TIMESTAMP"

        // 欧泰健康可能使用的action
        const val ACTION_OTA_BG = "com.outai.health.BG_ESTIMATE"
    }

    // 接收到的血糖数据
    private val _latestReading = MutableStateFlow<GlucoseReading?>(null)
    val latestReading: StateFlow<GlucoseReading?> = _latestReading

    data class GlucoseReading(
        val timestamp: Long,
        val value: Double,      // mg/dL
        val valueMmol: Double,  // mmol/L
        val source: String = "companion"
    )

    private var receiver: BroadcastReceiver? = null

    /**
     * 启动监听
     */
    fun startListening() {
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleBroadcast(intent)
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CGM_BG)
            addAction(ACTION_CGM_BG_FOLLOWER)
            addAction(ACTION_OTA_BG)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            Log.d(TAG, "Companion App接收器已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动接收器失败: ${e.message}")
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // 忽略
            }
        }
        receiver = null
    }

    /**
     * 处理接收到的广播
     */
    private fun handleBroadcast(intent: Intent) {
        try {
            val action = intent.action
            Log.d(TAG, "收到广播: $action")

            // 尝试从不同键获取血糖值
            var glucoseValue: Double? = null
            var timestamp: Long = System.currentTimeMillis()

            // 方式1: 直接获取血糖值
            if (intent.hasExtra(KEY_BG_ESTIMATE)) {
                glucoseValue = intent.getDoubleExtra(KEY_BG_ESTIMATE, 0.0)
            }

            // 方式2: 从bundle获取
            if (glucoseValue == null || glucoseValue == 0.0) {
                val extras = intent.extras
                if (extras != null) {
                    // 尝试不同的键
                    for (key in listOf("glucose", "bg", "value", "estimate", "mgdl")) {
                        val value = extras.getDouble(key, 0.0)
                        if (value > 0) {
                            glucoseValue = value
                            break
                        }
                    }
                }
            }

            // 获取时间戳
            if (intent.hasExtra(KEY_TIMESTAMP)) {
                timestamp = intent.getLongExtra(KEY_TIMESTAMP, timestamp)
            } else if (intent.hasExtra(KEY_DEX_TIMESTAMP)) {
                timestamp = intent.getLongExtra(KEY_DEX_TIMESTAMP, timestamp)
            }

            // 处理数据
            if (glucoseValue != null && glucoseValue > 0) {
                val reading = GlucoseReading(
                    timestamp = timestamp,
                    value = glucoseValue,
                    valueMmol = glucoseValue / 18.0,
                    source = "companion"
                )

                _latestReading.value = reading
                Log.d(TAG, "收到血糖数据: ${reading.valueMmol} mmol/L")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理广播失败: ${e.message}")
        }
    }

    /**
     * 手动注入血糖数据（用于测试或其他数据源）
     */
    fun injectReading(glucoseMmol: Double, timestamp: Long = System.currentTimeMillis()) {
        val reading = GlucoseReading(
            timestamp = timestamp,
            value = glucoseMmol * 18.0,
            valueMmol = glucoseMmol,
            source = "manual"
        )
        _latestReading.value = reading
        Log.d(TAG, "手动注入血糖: $glucoseMmol mmol/L")
    }
}
