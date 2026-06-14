package com.tangdun.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 重启接收器 — 保持后台服务持续运行 (参考 xDrip+ WakeLockTrampoline)
 *
 * 触发场景:
 *   1. 用户从最近任务列表滑掉App → onTaskRemoved → 本Receiver → 重启前台服务
 *   2. 设备开机 → BOOT_COMPLETED → 本Receiver → 启动前台服务
 *   3. 系统定时唤醒 → AlarmManager → 本Receiver → 检查并重启
 *
 * xDrip+ 模式: 通过 AlarmManager 每15分钟检查一次服务状态并重启
 */
class RestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RestartRx"

        fun scheduleAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, RestartReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 每15分钟检查一次
            try {
                alarmManager.setInexactRepeating(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    15 * 60 * 1000L,
                    15 * 60 * 1000L,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限
                alarmManager.setInexactRepeating(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    15 * 60 * 1000L,
                    15 * 60 * 1000L,
                    pendingIntent
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到重启信号: ${intent.action}")

        try {
            // 检查是否需要重启前台服务
            if (!isServiceRunning(context)) {
                Log.i(TAG, "前台服务未运行，启动中...")
                GlucoseForegroundService.start(context)
            } else {
                Log.d(TAG, "前台服务已在运行")
            }

            // 检查通知监听服务
            if (!com.tangdun.app.sync.CGMNotificationListener.isEnabled(context)) {
                Log.w(TAG, "通知监听未启用")
            } else {
                com.tangdun.app.sync.CGMNotificationListener.requestRebind(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "重启失败: ${e.message}")
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val services = am.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == GlucoseForegroundService::class.java.name }
    }
}
