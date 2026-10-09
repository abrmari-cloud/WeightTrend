package com.weighttrend.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.weighttrend.core.MiScaleFrame
import com.weighttrend.data.Settings

/**
 * Listens to the scale's Bluetooth advertisements. No pairing or connection is
 * needed: the scale broadcasts each result openly, so Zepp Life can stay
 * installed alongside.
 */
object ScaleScanner {
    private const val TAG = "ScaleScanner"
    val SERVICE: ParcelUuid = ParcelUuid.fromString(MiScaleFrame.SERVICE_UUID)

    fun hasScanPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Extracts a scale reading from a scan result, if it carries one. */
    fun frameOf(result: ScanResult): MiScaleFrame? =
        result.scanRecord?.getServiceData(SERVICE)?.let { MiScaleFrame.parse(it) }

    fun looksLikeScale(result: ScanResult): Boolean {
        val name = result.scanRecord?.deviceName?.uppercase() ?: ""
        return frameOf(result) != null || name.startsWith("MIBFS") || name.startsWith("MIBCS")
    }

    private fun backgroundIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, ScanResultReceiver::class.java).setAction(ScanResultReceiver.ACTION),
            // Mutable: the system adds the scan results as extras.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /**
     * (Re)starts the always-on background listener for the bound scale. The
     * system keeps this scan alive while the app is not running and wakes the
     * app only when the scale broadcasts. Survives until reboot (BootReceiver
     * restarts it).
     */
    @SuppressLint("MissingPermission")
    fun ensureBackgroundScan(context: Context): Boolean {
        val address = Settings(context).scaleAddress ?: return false
        if (!hasScanPermission(context)) return false
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
        val pi = backgroundIntent(context)
        runCatching { scanner.stopScan(pi) }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val rc = runCatching { scanner.startScan(listOf(filter), settings, pi) }.getOrElse {
            Log.w(TAG, "startScan failed", it); return false
        }
        Log.i(TAG, "background scan for $address started rc=$rc")
        return rc == 0
    }

    @SuppressLint("MissingPermission")
    fun stopBackgroundScan(context: Context) {
        if (!hasScanPermission(context)) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(backgroundIntent(context)) }
    }

    /** A short-lived scan while the app is open: live weight display and scale discovery. */
    class LiveScan(
        private val context: Context,
        private val onResult: (ScanResult) -> Unit,
    ) {
        private var running = false
        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)
            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "live scan failed: $errorCode") }
        }

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            if (running || !hasScanPermission(context) || !isBluetoothOn(context)) return false
            val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            running = runCatching { scanner.startScan(null, settings, callback); true }.getOrDefault(false)
            return running
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            if (!running) return
            running = false
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
            }
        }
    }
}
