package com.tangdun.app.sync

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.tangdun.app.R
import com.tangdun.app.TangDunApp
import com.tangdun.app.ui.MainActivity
import com.tangdun.app.util.SettingsManager
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 胰岛素提醒Worker
 *
 * 功能：
 * 1. 根据用户设置的提醒时间发送通知
 * 2. 每天定时检查
 */
class InsulinReminderWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val WORK_NAME = "insulin_reminder"
        private const val NOTIFICATION_ID_REMINDER = 1001

        /**
         * 启动每日提醒检查
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InsulinReminderWorker>(
                1, TimeUnit.DAYS
            )
            .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * 计算到下一个提醒时间的延迟
         */
        private fun calculateInitialDelay(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            if (target.before(now)) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val settingsManager = SettingsManager(applicationContext)

        if (!settingsManager.isInsulinReminderEnabled()) {
            return Result.success()
        }

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val currentMinute = Calendar.getInstance().get(Calendar.MINUTE)
        val currentTime = String.format("%02d:%02d", currentHour, currentMinute)

        // 检查是否到了提醒时间
        val reminderTimes = listOf(
            settingsManager.getInsulinReminderMorning(),
            settingsManager.getInsulinReminderNoon(),
            settingsManager.getInsulinReminderEvening(),
            settingsManager.getInsulinReminderNight()
        )

        val mealName = when (currentTime) {
            settingsManager.getInsulinReminderMorning() -> "早餐"
            settingsManager.getInsulinReminderNoon() -> "午餐"
            settingsManager.getInsulinReminderEvening() -> "晚餐"
            settingsManager.getInsulinReminderNight() -> "睡前"
            else -> null
        }

        if (mealName != null) {
            sendReminderNotification(mealName)
        }

        return Result.success()
    }

    private fun sendReminderNotification(mealName: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, TangDunApp.CHANNEL_ID_REMINDER)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("胰岛素注射提醒")
            .setContentText("${mealName}前请记得注射胰岛素")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID_REMINDER, notification)
    }
}
