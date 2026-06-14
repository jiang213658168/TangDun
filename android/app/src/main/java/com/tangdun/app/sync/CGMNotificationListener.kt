package com.tangdun.app.sync

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.domain.algorithm.RealTimeGlucoseMonitor
import com.tangdun.app.service.GlucoseAlarmService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * CGM通知监听 — 移植自 xDrip+ UiBasedCollector.java
 *
 * 原理: 欧态健康(com.ottai.tag)不是发广播，而是通过Android通知栏发布血糖数据。
 * 本服务读取通知中所有TextView的文字，提取血糖值并保存。
 *
 * 官方支持: xDrip+源码 UiBasedCollector.java line 111 确认 com.ottai.tag 在支持列表中
 *
 * 需要: 用户授予通知访问权限 (设置→通知访问→糖盾)
 */
class CGMNotificationListener : NotificationListenerService() {

    // 绑定Service生命周期的协程作用域，避免每次通知创建新Scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 实时血糖监测引擎 (卡尔曼滤波 + 噪声检测 + 趋势计算)
    private val monitor by lazy { RealTimeGlucoseMonitor(this) }

    companion object {
        private const val TAG = "CGMNotify"

        // 所有CGM品牌 — 移植自 xDrip+ UiBasedCollector.java 完整列表
        private val TARGET_PACKAGES = setOf(
            // 欧态/Aidex/微泰
            "com.ottai.tag", "com.ottai.seas",
            "com.microtech.aidexx", "com.microtech.aidexx.mgdl",
            "com.microtech.aidexx.linxneo.mmoll", "com.microtech.aidexx.equil.mmoll",
            "com.microtech.aidexx.diaexport.mmoll", "com.microtech.aidexx.smart.mmoll",
            // Dexcom G6/G7/One+/Stelo
            "com.dexcom.g6", "com.dexcom.g6.region1.mmol", "com.dexcom.g6.region2.mgdl",
            "com.dexcom.g6.region3.mgdl", "com.dexcom.g6.region4.mmol", "com.dexcom.g6.region5.mmol",
            "com.dexcom.g6.region6.mgdl", "com.dexcom.g6.region7.mmol", "com.dexcom.g6.region8.mmol",
            "com.dexcom.g6.region9.mgdl", "com.dexcom.g6.region10.mgdl", "com.dexcom.g6.region11.mmol",
            "com.dexcom.g7", "com.dexcom.dexcomone", "com.dexcom.d1plus", "com.dexcom.stelo",
            // CamAPS
            "com.camdiab.fx_alert.mmoll", "com.camdiab.fx_alert.mgdl",
            "com.camdiab.fx_alert.hx.mmoll", "com.camdiab.fx_alert.hx.mgdl",
            "com.camdiab.fx_alert.mmoll.ca",
            // Medtronic
            "com.medtronic.diabetes.guardian", "com.medtronic.diabetes.guardianconnect",
            "com.medtronic.diabetes.guardianconnect.us",
            "com.medtronic.diabetes.minimedmobile.eu", "com.medtronic.diabetes.minimedmobile.us",
            "com.medtronic.diabetes.simplera.eu",
            // Eversense
            "com.senseonics.gen12androidapp", "com.senseonics.androidapp",
            "com.senseonics.eversense365.us",
            // 三诺/硅基/其他
            "com.sinocare.cgm.ce", "com.sinocare.ican.health.ce",
            "com.sinocare.ican.health.ru",
            "com.suswel.ai", "com.glucotech.app.android",
            "com.kakaohealthcare.pasta",
        )

        private const val MIN_GLUCOSE_MGDL = 40
        private const val MAX_GLUCOSE_MGDL = 405
        private var lastValue = 0
        private var lastTimestamp = 0L
        @Volatile var lastActivityTime = 0L  // 最近一次收到通知的时间

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat?.contains(context.packageName) == true
        }

        fun openSettings(context: Context) {
            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** 强制重新绑定通知监听 */
        fun requestRebind(context: Context) {
            if (isEnabled(context)) {
                context.startService(Intent(context, CGMNotificationListener::class.java))
            }
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
        lastActivityTime = System.currentTimeMillis()
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "通知监听断开! 尝试重新绑定...")
        // Android 会自动重新绑定，但如果失败需要用户手动操作
        requestRebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        if (pkg !in TARGET_PACKAGES) return

        try {
            val notification = sbn.notification ?: return
            lastActivityTime = System.currentTimeMillis()
            Log.d(TAG, "收到通知: $pkg ongoing=${sbn.isOngoing}")

            val mgdl = extractGlucose(notification)
            if (mgdl > 0) {
                val glucoseMmol = mgdl / 18.0
                val now = System.currentTimeMillis()

                // 防重复 (值相同+5分钟内跳过)
                if (mgdl == lastValue && (now - lastTimestamp) < 300_000) {
                    Log.d(TAG, "跳过重复: $glucoseMmol")
                    return
                }
                lastValue = mgdl
                lastTimestamp = now

                Log.i(TAG, "血糖: ${String.format("%.1f", glucoseMmol)} mmol/L ($mgdl mg/dL)")

                serviceScope.launch {
                    try {
                        // 通过实时监测引擎处理 (卡尔曼滤波+噪声检测+趋势)
                        val processed = monitor.ingest(glucoseMmol, now, "cgm_notify")
                        val dao = TangDunApp.getDatabase(this@CGMNotificationListener).glucoseDao()

                        // 使用处理后的校准值 (或回退到原始值)
                        val saveValue = processed?.calibratedValue ?: glucoseMmol
                        val saveTrend = processed?.trend?.arrow

                        val latest = dao.getLatest()
                        if (latest != null && latest.source == "cgm_notify" && kotlin.math.abs(now - latest.timestamp) < 55_000) return@launch

                        dao.insert(GlucoseRecord(
                            timestamp = now,
                            value = saveValue,
                            source = "cgm_notify",
                            trend = saveTrend,
                            rawData = processed?.rawValue
                        ))

                        // 质量评分≥50才触发警报 (过滤噪声误报)
                        // 质量达标OR严重低血糖→触发告警 (严重情况永不静默)
                        if (processed != null && (processed.qualityScore >= 50 || saveValue < 3.0)) {
                            val pred30 = saveValue + (processed.roc * 30)
                            try { GlucoseAlarmService(this@CGMNotificationListener).checkAndAlarm(saveValue, saveTrend, pred30) } catch (e: Exception) { Log.w(TAG, "警报检查失败: ${e.message}") }
                        }
                    } catch (e: Exception) { Log.e(TAG, "保存失败: ${e.message}") }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "处理通知失败: ${e.message}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 从通知中提取血糖值 — 移植自 UiBasedCollector.tryExtractString()
     */
    private fun extractGlucose(notification: Notification): Int {
        // 方法1: extras文本
        val title = notification.extras?.getString(Notification.EXTRA_TITLE) ?: ""
        val text = notification.extras?.getString(Notification.EXTRA_TEXT) ?: ""
        val mgdl = tryExtract(title) + tryExtract(text)
        if (mgdl > 0) return mgdl

        // 方法2: RemoteViews (富通知)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notification.contentView?.let { cv ->
                try {
                    val root = cv.apply(this, null) as? ViewGroup ?: return@let
                    val texts = mutableListOf<TextView>()
                    collectTextViews(texts, root)
                    for (tv in texts) {
                        val t = tv.text?.toString() ?: ""
                        val v = tryExtract(t)
                        if (v in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL) return v
                    }
                } catch (_: Exception) {}
            }
        }
        return -1
    }

    /**
     * 从文本中提取血糖值 — 移植自 UiBasedCollector
     */
    private fun tryExtract(raw: String): Int {
        if (raw.isBlank()) return -1
        // CGM超出范围标记: LOW→39mg/dL, HIGH→406mg/dL (触发告警但不丢数据)
        val upper = raw.uppercase()
        if (upper.contains("LOW") || upper.contains("LO")) return 39
        if (upper.contains("HIGH") || upper.contains("HI")) return 406

        // 去掉单位、箭头等 — 移植自 basicFilterString() + arrowFilterString()
        var s = raw
            .replace(" ", " ").replace("⁠", "")
            .replace("mmol/L", "", true).replace("mmol/l", "", true)
            .replace("mg/dL", "", true).replace("mg/dl", "", true)
            .replace("≤", "").replace("≥", "").replace("\\", "/")
            // 去掉常见箭头 Unicode
            .replace(Regex("[←-⇿✀-➿⤀-⥿⬀-⯿]"), "")
            .trim()

        // 尝试整数(mg/dL)
        s.toIntOrNull()?.let { if (it in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL) return it }

        // 尝试小数(mmol/L) → 转mg/dL
        if (s.matches(Regex("[0-9]+[.,][0-9]+"))) {
            val mmol = s.replace(",", ".").toDoubleOrNull()
            if (mmol != null && mmol in 1.0..22.0) return (mmol * 18.0).toInt()
        }
        return -1
    }

    private fun collectTextViews(output: MutableList<TextView>, parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView) output.add(child)
            else if (child is ViewGroup && child.visibility == View.VISIBLE) collectTextViews(output, child)
        }
    }
}
