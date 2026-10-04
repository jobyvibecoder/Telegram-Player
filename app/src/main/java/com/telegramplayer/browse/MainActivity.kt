package com.telegramplayer.browse

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.telegramplayer.R

/**
 * Main Activity hosting the Leanback BrowseSupportFragment.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request bypassing battery optimizations once on startup
        com.telegramplayer.data.BatteryOptimizationHelper.promptOnceOnStartup(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, MainBrowseFragment())
                .commit()
        }
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}
