package com.weighttrend.hc

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.weighttrend.core.Measurement
import com.weighttrend.data.Repository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Writes new weigh-ins (scale and manual) to Health Connect so that other apps
 * can read them. Imported history is not written, to avoid duplicating data
 * other apps (e.g. Libra) may already have put there.
 */
object HealthConnectSync {
    private const val TAG = "HealthConnectSync"
    private val mutex = Mutex()

    val PERMISSIONS = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
    )

    private val SCALE = Device(
        manufacturer = "Xiaomi",
        model = "Mi Body Composition Scale 2",
        type = Device.TYPE_SCALE,
    )

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    /** Pushes everything not yet synced. Returns the number of weigh-ins written, or -1 on error. */
    suspend fun trySync(context: Context, repo: Repository): Int = mutex.withLock {
        if (!repo.settings.healthConnectEnabled) return 0
        try {
            if (!hasPermissions(context)) return 0
            val pending = repo.measurements.value.filter {
                !it.healthConnectSynced &&
                    (it.source == Measurement.Source.SCALE || it.source == Measurement.Source.MANUAL)
            }
            if (pending.isEmpty()) return 0
            val client = HealthConnectClient.getOrCreate(context)
            for (chunk in pending.chunked(100)) {
                client.insertRecords(chunk.flatMap { toRecords(it) })
                repo.markHealthConnectSynced(chunk.map { it.id })
            }
            pending.size
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            -1
        }
    }

    private fun toRecords(m: Measurement): List<Record> {
        val time = Instant.ofEpochMilli(m.timestampMs)
        val offset = ZoneId.systemDefault().rules.getOffset(time)
        // Version 2 once body composition is known, so the update replaces version 1.
        val version = if (m.fatPercent != null) 2L else 1L
        fun meta(kind: String) = if (m.source == Measurement.Source.SCALE) {
            Metadata.autoRecorded(clientRecordId = "wt-$kind-${m.id}", clientRecordVersion = version, device = SCALE)
        } else {
            Metadata.manualEntry(clientRecordId = "wt-$kind-${m.id}", clientRecordVersion = version)
        }
        return buildList {
            add(WeightRecord(time = time, zoneOffset = offset, weight = Mass.kilograms(m.weightKg), metadata = meta("w")))
            m.fatPercent?.let {
                add(BodyFatRecord(time = time, zoneOffset = offset, percentage = Percentage(it), metadata = meta("f")))
            }
            m.boneKg?.let {
                add(BoneMassRecord(time = time, zoneOffset = offset, mass = Mass.kilograms(it), metadata = meta("b")))
            }
        }
    }
}
