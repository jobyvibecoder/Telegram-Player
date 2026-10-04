package com.telegramplayer

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.telegramplayer.data.ShutdownReceiver
import com.telegramplayer.data.TdClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TelegramPlayerApp : Application(), DefaultLifecycleObserver {
    
    lateinit var tdClientManager: TdClientManager
        private set

    private val appScope = CoroutineScope(Dispatchers.Main)
    private var backgroundJob: Job? = null

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        tdClientManager = TdClientManager(this).apply {
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            initialize()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Start background service to warm up and keep connection alive
        com.telegramplayer.data.TelegramBackgroundService.start(this)

        // Dynamically register shutdown/screen-off receiver.
        // ACTION_SCREEN_OFF requires dynamic registration on modern Android.
        val shutdownFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(ShutdownReceiver(), shutdownFilter)
        
        // Sync Malayalam posters in background
        appScope.launch {
            com.telegramplayer.data.MalayalamPosterRepository.getInstance(this@TelegramPlayerApp).syncIfNeeded()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App is in foreground — restore online flag if it was set to
        // false by ShutdownReceiver on a screen-off that didn't lead
        // to an actual shutdown (e.g., screen timeout then wake).
        try {
            tdClientManager.setOnline(true)
        } catch (_: Exception) { }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        // Removed TDLib shutdown to allow MX Player to continue streaming
    }

    companion object {
        lateinit var instance: TelegramPlayerApp
            private set

        fun getTdClient(): TdClientManager = instance.tdClientManager
    }
}
