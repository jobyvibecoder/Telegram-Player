package com.telegramplayer.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.telegramplayer.TelegramPlayerApp

/**
 * Catches device shutdown and screen-off events to close TDLib gracefully
 * BEFORE Fire OS hard-kills the network socket. This prevents "ghost sessions"
 * on Telegram's server that block new connections for 3-5 minutes after reboot.
 *
 * Registered both in AndroidManifest (for ACTION_SHUTDOWN) and dynamically
 * in TelegramPlayerApp (for ACTION_SCREEN_OFF, which requires dynamic registration).
 */
class ShutdownReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ShutdownReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_SHUTDOWN, "android.intent.action.QUICKBOOT_POWEROFF" -> {
                // TV is powering down — close TDLib synchronously to invalidate
                // the server-side session. Must be fast (≤2s) because the OS
                // will kill us shortly after this broadcast.
                Log.d(TAG, "Device shutting down. Closing TDLib gracefully...")
                try {
                    TelegramPlayerApp.getTdClient().closeGracefully()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during graceful shutdown", e)
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                // Screen off may precede shutdown on Fire OS (or could just be
                // a screen timeout). Set offline flag to tell Telegram server
                // "this client is going away" without fully closing the session.
                // If the TV does resume, a quick online=true restores instantly.
                Log.d(TAG, "Screen off. Setting TDLib offline...")
                try {
                    TelegramPlayerApp.getTdClient().setOnline(false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting offline on screen off", e)
                }
            }
        }
    }
}
