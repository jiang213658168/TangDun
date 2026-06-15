package com.tangdun.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.graphics.Bitmap
import com.tangdun.app.R
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.entity.GlucoseRecord
import com.tangdun.app.domain.algorithm.DallaManModel
import com.tangdun.app.ui.MainActivity
import com.tangdun.app.util.SettingsManager
import com.tangdun.app.widget.NotificationChartRenderer
import kotlinx.coroutines.*

/**
 * 前台服务 — 保持App在后台持续运行 (参考 xDrip+ ForegroundServiceStarter)
 *
 * xDrip+ 模式:
 *   1. startForeground() 显示持久通知
 *   2. START_STICKY: 被杀后自动重启
 *   3. WakeLock 保持CPU运行
 *   4. AlarmManager 定时唤醒
 *
 * Android 14+ (API 34): 必须声明 foregroundServiceType
 * Android 10+ (API 29): startForeground(id, notification, FOREGROUND_SERVICE_TYPE_*)
 */
class GlucoseForegroundService : Service() {

    companion object {
        private const val TAG = "GlucoseFGS"
        const val CHANNEL_ID = "tangdun_foreground"
        const val NOTIFICATION_ID = 8811

        fun start(context: Context) {
            val intent = Intent(context, GlucoseForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlucoseForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "前台服务创建")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台
        val notification = buildNotification(null, null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalArgumentException) {
            // 降级: 某些设备不支持该类型
            startForeground(NOTIFICATION_ID, notification)
        }

        // 持有WakeLock 防止CPU休眠
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TangDun:GlucoseMonitor")
        wakeLock.acquire(10 * 60 * 1000L) // 10分钟，超时自动释放

        // 定时更新通知 (每5分钟刷新血糖)
        serviceScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                try {
                    updateNotification()
                } catch (e: Exception) {
                    Log.w(TAG, "更新通知失败: ${e.message}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand flags=$flags startId=$startId")
        return START_STICKY  // xDrip+ 模式: 被杀后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        Log.i(TAG, "前台服务销毁")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务中滑掉 → 重启服务 (xDrip+模式)
        Log.w(TAG, "任务被移除，尝试重启...")
        val restartIntent = Intent(this, RestartReceiver::class.java)
        sendBroadcast(restartIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "血糖监测",
                NotificationManager.IMPORTANCE_LOW  // LOW: 不发声不震动，仅状态栏图标
            ).apply {
                description = "糖盾血糖监测持续运行中"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(latest: com.tangdun.app.data.local.entity.GlucoseRecord?, chart: Bitmap?): Notification {
        val latestGlucose = latest?.value
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settings = SettingsManager(this)
        val low = settings.getTargetLow()
        val high = settings.getTargetHigh()

        val trendEmoji = latest?.trend?.let { t ->
            when(t) { "rising_fast"->"⬆️" "rising"->"↗️" "stable"->"➡️" "falling"->"↘️" "falling_fast"->"⬇️" else->"" }
        } ?: ""
        val title = if (latestGlucose != null) {
            "血糖$trendEmoji ${String.format("%.1f", latestGlucose)} mmol/L"
        } else {
            "糖盾监测中"
        }

        val content = if (latestGlucose != null) {
            val status = when {
                latestGlucose < low -> "⚠ 低血糖"
                latestGlucose > high -> "⚠ 高血糖"
                else -> "✓ 正常范围"
            }
            "$status | 目标 ${String.format("%.1f", low)}-${String.format("%.1f", high)}"
        } else {
            "等待CGM数据..."
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 通知栏曲线图 (参考 xDrip+ 持久通知sparkline)
        if (chart != null) {
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(chart))
        }

        return builder.build()
    }

    private suspend fun updateNotification() = withContext(Dispatchers.IO) {
        try {
            val dao = TangDunApp.getDatabase(this@GlucoseForegroundService).glucoseDao()
            val latest = dao.getLatest()
            val settings = SettingsManager(this@GlucoseForegroundService)

            // 生成通知栏曲线图 (含近期饮食/胰岛素)
            var chart: Bitmap? = null
            try {
                val history = dao.getRecent(36)  // 最近3小时
                if (history.size >= 4) {
                    val now = System.currentTimeMillis()
                    val db = TangDunApp.getDatabase(this@GlucoseForegroundService)
                    // 读取近期饮食和胰岛素(2h内) → DallaMan有上下文
                    val recentMeals = db.mealDao().getByTimeRange(now - 4 * 3600_000L, now)
                        .takeLast(3).map { DallaManModel.MealInput((now - it.timestamp) / 60000.0, it.totalCarbs, it.avgGi) }
                    val recentInsulin = db.insulinDao().getSince(now - 4 * 3600_000L)
                        .filter { it.insulinType == "rapid" }.takeLast(5)
                        .map { DallaManModel.InsulinInput((now - it.timestamp) / 60000.0, it.doseUnits) }

                    val g = history.last().value
                    val weight = settings.getWeightKg().toDouble()
                    val model = DallaManModel()
                    val curve = model.predict(g, 5.0, recentMeals, recentInsulin, 60, 5,
                        DallaManModel.Parameters.forUser(bodyWeight = weight))
                    chart = NotificationChartRenderer.render(history, curve,
                        settings.getTargetLow().toDouble(), settings.getTargetHigh().toDouble())
                }
            } catch (e: Exception) { Log.w(TAG, "图表生成失败: ${e.message}") }

            val notification = buildNotification(latest, chart)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败: ${e.message}")
        }
    }
}
