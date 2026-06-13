package com.tangdun.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tangdun.app.data.local.AppDatabase
import dagger.hilt.android.HiltAndroidApp

/**
 * 糖盾Application
 *
 * 提供全局Application实例和数据库单例
 */
@HiltAndroidApp
class TangDunApp : Application() {

    companion object {
        const val CHANNEL_ID_ALERTS = "tangdun_alerts"
        const val CHANNEL_ID_SYNC = "tangdun_sync"
        const val CHANNEL_ID_REMINDER = "tangdun_reminder"

        @Volatile private var database: AppDatabase? = null

        /** 获取数据库单例（线程安全，供BroadcastReceiver等无法Hilt注入的场景使用） */
        fun getDatabase(context: android.content.Context): AppDatabase {
            return database ?: synchronized(this) {
                database ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppDatabase.DATABASE_NAME
                )
                .fallbackToDestructiveMigration()
                .build().also { database = it }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    /** 全局Application实例 */
    lateinit var instance: TangDunApp
        private set

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "血糖预警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "血糖异常预警通知"
                enableVibration(true)
            }

            val syncChannel = NotificationChannel(
                CHANNEL_ID_SYNC,
                "数据同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "数据同步通知"
            }

            val reminderChannel = NotificationChannel(
                CHANNEL_ID_REMINDER,
                "用药提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "胰岛素注射提醒"
                enableVibration(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(alertChannel)
            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(reminderChannel)
        }
    }
}
