package com.weighttrend.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Background Bluetooth scans do not survive a reboot or an app update; restart it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                ScaleScanner.ensureBackgroundScan(context.applicationContext)
        }
    }
}
