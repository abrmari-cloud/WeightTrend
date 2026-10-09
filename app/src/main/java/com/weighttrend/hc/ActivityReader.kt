package com.weighttrend.hc

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.weighttrend.core.WeeklyAnalysis
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Reads daily steps and sleep from Health Connect (written there by Garmin
 * Connect and other apps) and averages them per week.
 *
 * Health Connect only gives other apps' data from 30 days before access was
 * granted, unless the "read history" permission is also granted.
 */
object ActivityReader {
    private const val TAG = "ActivityReader"
    const val HISTORY_PERMISSION = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HISTORY_PERMISSION,
    )
    private val REQUIRED = PERMISSIONS - HISTORY_PERMISSION

    data class Result(
        val steps: Map<LocalDate, Double>,      // week start -> average steps per day (days with steps)
        val sleep: Map<LocalDate, Double>,      // week start -> average hours per night (nights with sleep)
        val historyGranted: Boolean,
        val weeksWithoutAccess: Int,
    )

    suspend fun granted(context: Context): Set<String> =
        if (!HealthConnectSync.isAvailable(context)) emptySet()
        else HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun hasAccess(context: Context): Boolean = granted(context).containsAll(REQUIRED)

    suspend fun read(context: Context, weeks: Int = 26, zone: ZoneId = ZoneId.systemDefault()): Result {
        val client = HealthConnectClient.getOrCreate(context)
        val history = granted(context).contains(HISTORY_PERMISSION)
        val thisWeek = WeeklyAnalysis.mondayOf(LocalDate.now(zone))
        val steps = mutableMapOf<LocalDate, Double>()
        val sleep = mutableMapOf<LocalDate, Double>()
        var denied = 0

        for (i in weeks - 1 downTo 0) {
            val week = thisWeek.minusWeeks(i.toLong())
            val from = week.atStartOfDay()
            val to = week.plusDays(7).atStartOfDay()
            try {
                // Steps: daily totals, averaged over days that have any steps.
                val days = client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                ).mapNotNull { it.result[StepsRecord.COUNT_TOTAL]?.takeIf { n -> n > 0 } }
                if (days.isNotEmpty()) steps[week] = days.average()

                // Sleep: sessions grouped by the night they end on.
                val nights = mutableMapOf<LocalDate, Duration>()
                var token: String? = null
                do {
                    val resp = client.readRecords(
                        ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(from, to),
                            pageToken = token,
                        )
                    )
                    for (s in resp.records) {
                        val night = s.endTime.atZone(zone).toLocalDate()
                        nights[night] = (nights[night] ?: Duration.ZERO) + Duration.between(s.startTime, s.endTime)
                    }
                    token = resp.pageToken
                } while (token != null)
                if (nights.isNotEmpty()) sleep[week] = nights.values.map { it.toMinutes() / 60.0 }.average()
            } catch (e: SecurityException) {
                // Older than Health Connect lets us read without the history permission.
                denied++
            } catch (e: Exception) {
                Log.w(TAG, "week $week", e)
            }
        }
        return Result(steps, sleep, history, denied)
    }
}
