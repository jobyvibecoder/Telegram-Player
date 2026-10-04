package com.telegramplayer.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.telegramplayer.R

/**
 * Foreground service to keep the streaming proxy running.
 */
class StreamingProxyService : Service() {

    companion object {
        private const val TAG = "StreamingProxyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "streaming_channel"
    }

    private var proxyServer: StreamingProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        startProxyServer()
    }

    private fun startProxyServer() {
        try {
            proxyServer = StreamingProxyServer.getInstance()
            proxyServer?.start()
            Log.d(TAG, "Streaming proxy started on port ${proxyServer?.listeningPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Telegram video streaming"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        proxyServer?.stop()
        Log.d(TAG, "Streaming proxy stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
