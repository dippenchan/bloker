package com.example.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.URL

class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "blocker_sync"
        const val NOTI_ID = 1001
        private const val REMOTE_URL =
            "https://raw.githubusercontent.com/dippenchan/bloker/main/switch.txt"
        private const val INTERVAL = 30_000L
    }

    @Volatile
    private var running = true
    private var pollingThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollingThread == null || pollingThread?.isAlive != true) {
            running = true
            startPolling()
        }
        return START_STICKY
    }

    private fun startPolling() {
        pollingThread = Thread {
            while (running) {
                try {
                    val conn = URL(REMOTE_URL).openConnection()
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val txt = conn.getInputStream()
                        .bufferedReader().use { it.readText() }.trim()
                    val on = txt.equals("on", ignoreCase = true)
                    Prefs.setBlockNoti(this, on)
                    Prefs.setBlockSms(this, on)
                } catch (_: Exception) {
                    // 网络失败，跳过这轮
                }
                Thread.sleep(INTERVAL)
            }
        }
        pollingThread?.start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "同步服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("拦截助手运行中")
            .setContentText("正在后台同步远程开关")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
