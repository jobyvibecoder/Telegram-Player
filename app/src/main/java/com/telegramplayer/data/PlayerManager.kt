package com.telegramplayer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit

/**
 * Manages video player selection and persistence.
 */
class PlayerManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_SELECTED_PLAYER = "selected_player_package"
        
        const val PACKAGE_ASK_EVERY_TIME = "ask_every_time"
        
        // Known players for recommended list or specific handling if needed
        const val MX_PLAYER_PACKAGE = "com.mxtech.videoplayer.ad"
        const val MX_PLAYER_PRO_PACKAGE = "com.mxtech.videoplayer.pro"
        const val VLC_PACKAGE = "org.videolan.vlc"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class PlayerInfo(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?
    )

    /**
     * Get list of installed apps that can handle video/mp4.
     */
    fun getAvailablePlayers(): List<PlayerInfo> {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse("file:///dummy.mp4"), "video/mp4")
        
        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        return resolveInfos.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            // Filter out our own app if it catches the intent (unlikely but good practice)
            if (packageName == context.packageName) return@mapNotNull null

            val label = resolveInfo.loadLabel(context.packageManager).toString()
            val icon = resolveInfo.loadIcon(context.packageManager)
            
            PlayerInfo(packageName, label, icon)
        }.distinctBy { it.packageName }
    }

    /**
     * Get the currently selected player package name.
     * Returns null if no preference saved or saved player is uninstalled.
     */
    fun getSelectedPlayer(): String? {
        val savedPackage = prefs.getString(KEY_SELECTED_PLAYER, PACKAGE_ASK_EVERY_TIME)
        if (savedPackage == PACKAGE_ASK_EVERY_TIME || savedPackage == null) {
            return PACKAGE_ASK_EVERY_TIME
        }
        
        if (isPackageInstalled(savedPackage)) {
            return savedPackage
        } else {
            // Cleanup if uninstalled
            prefs.edit { putString(KEY_SELECTED_PLAYER, PACKAGE_ASK_EVERY_TIME) }
            return PACKAGE_ASK_EVERY_TIME
        }
    }

    /**
     * Save the selected player package name.
     */
    fun saveSelectedPlayer(packageName: String) {
        prefs.edit { putString(KEY_SELECTED_PLAYER, packageName) }
    }
    
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
