package com.tangdun.app.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tangdun.app.R
import com.tangdun.app.ui.MainActivity

/**
 * 最小化前台服务 — 仅用于进程保活
 *
 * Android 16 兼容：FOREGROUND_SERVICE_DATA_SYNC 权限 + dataSync 类型
 * 不做任何数据操作，只确保糖盾进程不被系统标记为 stopped，
 * 从而能持续接收 xDrip+ 的显式广播。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startMyForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // 被杀后自动重启
    }

    private fun startMyForeground() {
        val channelId = "keepalive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "运行状态", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            999,
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("糖盾")
                .setContentText("血糖监测运行中")
                .setSmallIcon(R.drawable.ic_logo)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        )
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
