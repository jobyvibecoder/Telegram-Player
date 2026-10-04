package com.telegramplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"
    private const val PREFS_NAME = "battery_prefs"
    private const val KEY_HAS_PROMPTED = "has_prompted_battery_opt"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) {
            Toast.makeText(context, "Battery optimizations already bypassed.", Toast.LENGTH_SHORT).show()
            return
        }

        val packageName = context.packageName
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
            Log.d(TAG, "Launched ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Direct ignore battery optimization dialog failed, falling back to Settings", e)
            val fallbackIntent = Intent().apply {
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(fallbackIntent)
                Toast.makeText(
                    context,
                    "Please locate 'Telegram Player' and set it to 'Don't optimize'.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback Settings intent failed", ex)
                Toast.makeText(
                    context,
                    "Could not open battery settings. Please configure manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun promptOnceOnStartup(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPrompted = prefs.getBoolean(KEY_HAS_PROMPTED, false)
        if (!hasPrompted) {
            prefs.edit().putBoolean(KEY_HAS_PROMPTED, true).apply()
            requestIgnoreBatteryOptimizations(context)
        }
    }
}
