package com.example.music.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.music.MusicApplication
import com.example.music.R
import com.example.music.domain.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MusicDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("Загрузка музыки", 0, 0, true))
        val manager = (application as MusicApplication).container.downloadManager
        observeJob = scope.launch {
            manager.tasks.collectLatest { tasks ->
                val running = tasks.firstOrNull { it.status == DownloadStatus.RUNNING }
                val done = tasks.count { it.status == DownloadStatus.DONE }
                val total = tasks.size
                val notification = if (running != null) {
                    val percent = (running.progress * 100).toInt()
                    buildNotification(
                        title = "Скачивание ${done + 1} из $total",
                        progress = percent,
                        max = 100,
                        indeterminate = false,
                        text = running.path.substringAfterLast('/'),
                    )
                } else {
                    buildNotification("Загрузка музыки", done, total.coerceAtLeast(1), total == 0)
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Загрузка музыки",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        text: String? = null,
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "music_downloads"
        const val NOTIFICATION_ID = 42
    }
}
