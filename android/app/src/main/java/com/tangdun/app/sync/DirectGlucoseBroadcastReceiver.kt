package com.tangdun.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.domain.algorithm.RealTimeGlucoseMonitor
import com.tangdun.app.service.GlucoseAlarmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 血糖广播接收器
 *
 * 数据来源：xDrip+ 转发欧态健康/Aidex的血糖广播
 *
 * 依赖：用户需在 xDrip+ 中配置：
 *   设置 → Inter-app settings → ✅ Broadcast locally
 *   设置 → Inter-app settings → ✅ Compatible Broadcast（无权限模式）
 *   设置 → Inter-app settings → Identify receiver → com.tangdun.app
 *
 * 参考：xDrip+ BroadcastGlucose.java + Intents.java
 */
class DirectGlucoseBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GlucoseRx"
        private const val PREFS_LOG = "glucose_rx_log"

        // xDrip+ 广播（Intents.java）
        const val ACTION_XDRIP = "com.eveningoutpost.dexdrip.BgEstimate"
        // Aidex 直达广播（备用）
        const val ACTION_AIDEX = "com.microtechmd.cgms.aidex.action.BgEstimate"

        // 共享监测引擎 (跨广播调用保留状态)
        @Volatile private var monitorInstance: RealTimeGlucoseMonitor? = null
        private fun getMonitor(context: Context): RealTimeGlucoseMonitor {
            return monitorInstance ?: synchronized(this) {
                monitorInstance ?: RealTimeGlucoseMonitor(context.applicationContext).also { monitorInstance = it }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() 告诉系统等待异步操作完成，防止数据丢失
        val pending = goAsync()
        try {
            val action = intent.action ?: return
            val extras = intent.extras ?: return
            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            var glucoseMgDl = 0.0
            var timestamp = System.currentTimeMillis()
            var trend: String? = null
            var source = ""

            when (action) {
                // ── xDrip+ 格式 (BroadcastGlucose.java) ──
                ACTION_XDRIP -> {
                    glucoseMgDl = extras.getDouble("com.eveningoutpost.dexdrip.Extras.BgEstimate")
                    timestamp = extras.getLong("com.eveningoutpost.dexdrip.Extras.Time", timestamp)
                    // xDrip+ BgSlopeName: DoubleUp/SingleUp/Flat/SingleDown/DoubleDown 等
                    val slopeName = extras.getString("com.eveningoutpost.dexdrip.Extras.BgSlopeName")
                    trend = when (slopeName) {
                        "DoubleUp" -> "rising_fast"
                        "SingleUp", "FortyFiveUp" -> "rising"
                        "Flat" -> "stable"
                        "FortyFiveDown", "SingleDown" -> "falling"
                        "DoubleDown" -> "falling_fast"
                        else -> null
                    }
                    source = "xdrip"

                    // 备用键名
                    if (glucoseMgDl == 0.0) {
                        for (key in arrayOf("glucose", "sgv", "estimate", "mgdl", "BgEstimate")) {
                            val v = extras.getDouble(key)
                            if (v in 20.0..600.0) { glucoseMgDl = v; break }
                        }
                        for (key in arrayOf("timestamp", "time", "ts")) {
                            val t = extras.getLong(key)
                            if (t > 0) { timestamp = t; break }
                        }
                    }

                    Log.d(TAG, "[$now] xDrip+: ${String.format("%.1f", glucoseMgDl / 18.0)} mmol/L ${trend ?: ""}")
                }

                // ── Aidex 直达 (如果欧态不设setPackage) ──
                ACTION_AIDEX -> {
                    val bgValue = extras.getDouble("com.microtechmd.cgms.aidex.BgValue")
                    val bgType = extras.getString("com.microtechmd.cgms.aidex.BgType", "mg/dl")
                    timestamp = extras.getLong("com.microtechmd.cgms.aidex.Time", timestamp)
                    source = "aidex"

                    glucoseMgDl = when (bgType) {
                        "mmol/l" -> bgValue * 18.0
                        else -> bgValue
                    }
                    Log.d(TAG, "[$now] Aidex直达: ${String.format("%.1f", glucoseMgDl / 18.0)} mmol/L")
                }
            }

            // 保存到数据库
            if (glucoseMgDl in 20.0..600.0) {
                val glucoseMmol = glucoseMgDl / 18.0

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 通过实时监测引擎处理
                        val monitor = getMonitor(context)
                        val processed = monitor.ingest(glucoseMmol, timestamp, source)
                        val saveValue = processed?.calibratedValue ?: glucoseMmol
                        val saveTrend = processed?.trend?.arrow ?: trend

                        val dao = TangDunApp.getDatabase(context).glucoseDao()
                        val latest = dao.getLatest()
                        if (latest != null && kotlin.math.abs(timestamp - latest.timestamp) < 120_000) {
                            return@launch
                        }
                        dao.insert(GlucoseRecord(
                            timestamp = timestamp,
                            value = saveValue,
                            source = source,
                            trend = saveTrend,
                            rawData = processed?.rawValue
                        ))
                        Log.i(TAG, "已保存: ${String.format("%.1f", saveValue)} mmol/L ${saveTrend ?: ""} q=${processed?.qualityScore ?: 0}")
                        logEvent(context, "保存: ${String.format("%.1f", saveValue)} mmol/L", source)

                        if (processed != null && (processed.qualityScore >= 50 || saveValue < 3.0)) {
                            val pred30 = saveValue + (processed.roc * 30)
                            try { GlucoseAlarmService(context).checkAndAlarm(saveValue, saveTrend, pred30) } catch (e: Exception) { Log.w(TAG, "警报检查失败: ${e.message}") }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "保存失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理广播异常: ${e.message}")
        } finally {
            pending.finish()
        }
    }

    private fun logEvent(context: Context, msg: String, src: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_LOG, Context.MODE_PRIVATE)
            val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$now] $src: $msg\n"
            val old = prefs.getString("events", "") ?: ""
            prefs.edit().putString("events", (entry + old).take(2000)).apply()
        } catch (e: Exception) { Log.w(TAG, "日志记录失败: ${e.message}") }
    }
}
