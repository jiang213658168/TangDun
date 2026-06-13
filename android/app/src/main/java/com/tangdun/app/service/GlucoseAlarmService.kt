package com.tangdun.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tangdun.app.R
import com.tangdun.app.ui.MainActivity
import com.tangdun.app.util.SettingsManager

/**
 * 血糖闹钟服务
 *
 * 参考xDrip+的闹钟实现：
 * - 低血糖闹钟（紧急）
 * - 高血糖闹钟
 * - 快速下降/上升预警
 * - 传感器异常提醒
 */
class GlucoseAlarmService(private val context: Context) {

    companion object {
        private const val TAG = "GlucoseAlarm"

        // 通知ID
        const val NOTIFICATION_LOW = 1001
        const val NOTIFICATION_HIGH = 1002
        const val NOTIFICATION_RAPID_FALL = 1003
        const val NOTIFICATION_RAPID_RISE = 1004
        const val NOTIFICATION_REMINDER = 1005

        // 阈值
        const val URGENT_LOW = 3.0      // 紧急低血糖
        const val LOW = 3.9             // 低血糖
        const val HIGH = 10.0           // 高血糖
        const val URGENT_HIGH = 13.9    // 紧急高血糖

        // 变化率阈值 (mmol/L/min)
        const val RAPID_FALL = -0.10
        const val RAPID_RISE = 0.10
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val settingsManager = SettingsManager(context)

    /**
     * 检查并触发闹钟
     */
    fun checkAndAlarm(currentGlucose: Double, trend: String? = null) {
        if (!settingsManager.isAlertEnabled()) return

        when {
            // 紧急低血糖
            currentGlucose < URGENT_LOW -> {
                sendAlarm(
                    notificationId = NOTIFICATION_LOW,
                    title = "⚠️ 紧急低血糖！",
                    message = "血糖 ${String.format("%.1f", currentGlucose)} mmol/L，立即补充糖分！",
                    isUrgent = true
                )
            }
            // 低血糖
            currentGlucose < LOW -> {
                sendAlarm(
                    notificationId = NOTIFICATION_LOW,
                    title = "低血糖预警",
                    message = "血糖 ${String.format("%.1f", currentGlucose)} mmol/L，建议补充15g碳水",
                    isUrgent = false
                )
            }
            // 紧急高血糖
            currentGlucose > URGENT_HIGH -> {
                sendAlarm(
                    notificationId = NOTIFICATION_HIGH,
                    title = "⚠️ 紧急高血糖！",
                    message = "血糖 ${String.format("%.1f", currentGlucose)} mmol/L，请检测酮体",
                    isUrgent = true
                )
            }
            // 高血糖
            currentGlucose > HIGH -> {
                sendAlarm(
                    notificationId = NOTIFICATION_HIGH,
                    title = "高血糖预警",
                    message = "血糖 ${String.format("%.1f", currentGlucose)} mmol/L，注意饮食和胰岛素",
                    isUrgent = false
                )
            }
        }

        // 趋势预警
        when (trend) {
            "falling_fast" -> {
                sendAlarm(
                    notificationId = NOTIFICATION_RAPID_FALL,
                    title = "血糖快速下降",
                    message = "血糖正在快速下降，注意预防低血糖",
                    isUrgent = false
                )
            }
            "rising_fast" -> {
                sendAlarm(
                    notificationId = NOTIFICATION_RAPID_RISE,
                    title = "血糖快速上升",
                    message = "血糖正在快速上升，关注饮食和胰岛素",
                    isUrgent = false
                )
            }
        }
    }

    /**
     * 发送闹钟通知
     */
    private fun sendAlarm(notificationId: Int, title: String, message: String, isUrgent: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知声音
        val soundUri = if (isUrgent) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

        val builder = NotificationCompat.Builder(context, "tangdun_alerts")
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (isUrgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(if (isUrgent) longArrayOf(0, 500, 200, 500, 200, 500) else longArrayOf(0, 300))

        notificationManager.notify(notificationId, builder.build())
        Log.d(TAG, "发送闹钟: $title - $message")
    }

    /**
     * 清除闹钟
     */
    fun clearAlarm(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * 清除所有闹钟
     */
    fun clearAllAlarms() {
        notificationManager.cancelAll()
    }
}
