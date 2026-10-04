package com.telegramplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.telegramplayer.TelegramPlayerApp

class TelegramBackgroundService : Service() {
    companion object {
        private const val TAG = "TelegramBgService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "telegram_bg_channel"

        private var wakeLock: PowerManager.WakeLock? = null

        fun start(context: Context) {
            val intent = Intent(context, TelegramBackgroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TelegramBackgroundService", e)
            }
        }

        @Synchronized
        fun acquireWakeLock(context: Context) {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TelegramPlayer:WarmupWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                Log.d(TAG, "Acquiring WakeLock for 60s")
                wakeLock?.acquire(60_000L) // Safe 60s timeout
            }
        }

        @Synchronized
        fun releaseWakeLock() {
            if (wakeLock?.isHeld == true) {
                Log.d(TAG, "Releasing WakeLock early")
                wakeLock?.release()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate. Initializing TDLib connection.")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Acquire WakeLock to keep CPU awake during handshake
        acquireWakeLock(this)

        // Ensure TDLib client manager is initialized
        try {
            val app = applicationContext as TelegramPlayerApp
            app.tdClientManager.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TDLib client from Service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram Connection Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps Telegram connection warm in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram Player")
            .setContentText("Initializing connection in background...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        Log.d(TAG, "Service onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
