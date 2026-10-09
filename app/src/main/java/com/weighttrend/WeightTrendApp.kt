package com.weighttrend

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.weighttrend.ble.ScaleScanner

class WeightTrendApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_WEIGH_IN, "Новые взвешивания", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Make sure the background listener is running whenever the process starts.
        ScaleScanner.ensureBackgroundScan(this)
    }

    companion object {
        const val CHANNEL_WEIGH_IN = "weigh_in"
    }
}
