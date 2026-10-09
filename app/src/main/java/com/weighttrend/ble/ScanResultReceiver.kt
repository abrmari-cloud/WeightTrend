package com.weighttrend.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weighttrend.R
import com.weighttrend.WeightTrendApp
import com.weighttrend.core.Measurement
import com.weighttrend.data.Repository
import com.weighttrend.hc.HealthConnectSync
import com.weighttrend.ui.MainActivity
import com.weighttrend.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Woken by the system when the bound scale broadcasts while the app is closed. */
class ScanResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val extra: List<ScanResult>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }
        val results = extra ?: return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = Repository.get(app)
                var saved: Measurement? = null
                for (r in results) {
                    val frame = ScaleScanner.frameOf(r) ?: continue
                    repo.onScaleFrame(frame, System.currentTimeMillis())?.let { saved = it }
                }
                saved?.let { m ->
                    notify(app, m)
                    // Give the scale a few seconds to send the impedance reading,
                    // then push both to Health Connect in one go.
                    delay(5_000)
                    HealthConnectSync.trySync(app, repo)
                }
            } finally {
                pending.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, m: Measurement) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildString {
            append(Format.kg(m.weightKg))
            m.fatPercent?.let { append(" · жир ").append(Format.pct(it)) }
        }
        val n = Notification.Builder(context, WeightTrendApp.CHANNEL_WEIGH_IN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Взвешивание сохранено")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Same id for the weight-only and the with-impedance update of one weigh-in.
        NotificationManagerCompat.from(context).notify(m.id.toInt(), n)
    }

    companion object {
        const val ACTION = "com.weighttrend.SCALE_SCAN_RESULT"
    }
}
