package com.tangdun.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tangdun.app.R
import com.tangdun.app.TangDunApp
import com.tangdun.app.ui.MainActivity
import com.tangdun.app.util.SettingsManager

/**
 * 血糖预警服务 — 移植自 xDrip+ 闹钟系统
 *
 * 特性:
 * - 覆盖系统静音/勿扰模式
 * - 系统闹钟铃声 (大音量)
 * - 分级振动模式
 * - 低血糖重复闹钟
 */
class GlucoseAlarmService(private val context: Context) {

    companion object {
        private const val TAG = "GlucoseAlarm"
        const val NOTIFY_LOW = 1001
        const val NOTIFY_HIGH = 1002
        const val NOTIFY_SEVERE_LOW = 1003
        const val NOTIFY_SEVERE_HIGH = 1004
        const val NOTIFY_RAPID_FALL = 1005
        const val NOTIFY_RAPID_RISE = 1006
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun checkAndAlarm(glucoseMmol: Double, trend: String?, lastReadingTime: Long? = null) {
        val settings = SettingsManager(context)
        val sevLow = settings.getSevereLow().toDouble()
        val sevHigh = settings.getSevereHigh().toDouble()
        val low = settings.getTargetLow().toDouble()
        val high = settings.getTargetHigh().toDouble()

        // 严重低血糖
        if (glucoseMmol < sevLow) {
            sendAlarm(NOTIFY_SEVERE_LOW, "紧急低血糖!", "血糖 ${String.format("%.1f", glucoseMmol)} mmol/L，立即补充糖分！", true, true, longArrayOf(0, 500, 200, 500, 200, 500))
            val phone = settings.getEmergencyContactPhone()
            if (phone.isNotBlank()) {
                try { context.startActivity(Intent(Intent.ACTION_CALL).apply { data = Uri.parse("tel:$phone"); flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } catch (_: Exception) {}
            }
        } else if (glucoseMmol < low) {
            sendAlarm(NOTIFY_LOW, "低血糖预警", "血糖 ${String.format("%.1f", glucoseMmol)} mmol/L，建议补充15g碳水", false, false, longArrayOf(0, 300, 200, 300))
        } else if (glucoseMmol > sevHigh) {
            sendAlarm(NOTIFY_SEVERE_HIGH, "紧急高血糖!", "血糖 ${String.format("%.1f", glucoseMmol)} mmol/L，请检测酮体！", true, false, longArrayOf(0, 400, 200, 400))
        } else if (glucoseMmol > high) {
            sendAlarm(NOTIFY_HIGH, "高血糖预警", "血糖 ${String.format("%.1f", glucoseMmol)} mmol/L，注意饮食和胰岛素", false, false, longArrayOf(0, 200))
        }

        // 快速下降
        if (trend == "falling_fast") {
            sendAlarm(NOTIFY_RAPID_FALL, "血糖快速下降!",
                "正在快速下降，注意预防低血糖",
                false, true,
                longArrayOf(0, 300, 200, 300))
        }
        // 快速上升
        if (trend == "rising_fast") {
            sendAlarm(NOTIFY_RAPID_RISE, "血糖快速上升",
                "正在快速上升，关注饮食和胰岛素",
                false, false,
                longArrayOf(0, 200))
        }
    }

    private fun sendAlarm(
        id: Int, title: String, message: String,
        useAlarmSound: Boolean, repeat: Boolean, vibratePattern: LongArray
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 选择铃声: 严重→闹钟 普通→通知音
        val soundUri = if (useAlarmSound) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } else {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        }

        val builder = NotificationCompat.Builder(context, TangDunApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (useAlarmSound) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSound(soundUri, AudioManager.STREAM_ALARM)  // 用闹钟音频流
            .setVibrate(vibratePattern)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (repeat) {
            builder.setOngoing(true) // 重复提醒直到用户操作
        }

        // Android 8+ 覆盖勿扰
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(TangDunApp.CHANNEL_ID_ALERTS)
            if (channel != null && channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                // 升级通道重要性以覆盖勿扰
                nm.deleteNotificationChannel(TangDunApp.CHANNEL_ID_ALERTS)
                nm.createNotificationChannel(android.app.NotificationChannel(
                    TangDunApp.CHANNEL_ID_ALERTS, "血糖预警",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(soundUri, AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // 强制出声
                        .build())
                    enableVibration(true)
                })
            }
        }

        nm.notify(id, builder.build())
        Log.i(TAG, "[$id] $title → ${if (useAlarmSound) "🔔闹钟" else "🔉通知"}")

        // 额外振动
        if (useAlarmSound) {
            try {
                val v = if (Build.VERSION.SDK_INT >= 31) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                v.vibrate(VibrationEffect.createWaveform(vibratePattern, 0))
            } catch (_: Exception) {}
        }
    }

    fun clearAll() { nm.cancelAll() }
}
