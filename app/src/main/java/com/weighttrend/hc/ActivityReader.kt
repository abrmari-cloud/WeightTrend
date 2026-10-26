package com.weighttrend.hc

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.weighttrend.core.SleepNights
import com.weighttrend.core.WeeklyAnalysis
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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

    private val CORE = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    private val OPTIONAL = setOf(
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
    )
    val PERMISSIONS = CORE + OPTIONAL + HISTORY_PERMISSION
    private val REQUIRED = CORE

    private val AWAKE_STAGES = setOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    )

    /** Human names for apps that commonly write sleep. */
    fun appName(pkg: String): String = when (pkg) {
        SleepNights.GARMIN -> "Garmin Connect"
        "com.google.android.apps.fitness" -> "Google Fit"
        "com.samsung.android.wear.shealth", "com.sec.android.app.shealth" -> "Samsung Health"
        "com.google.android.apps.wellbeing" -> "Digital Wellbeing"
        "com.urbandroid.sleep" -> "Sleep as Android"
        "com.xiaomi.wearable", "com.mi.health" -> "Mi Fitness"
        "com.huami.watch.hmwatchmanager", "com.xiaomi.hm.health" -> "Zepp"
        "com.ouraring.oura" -> "Oura"
        "com.fitbit.FitbitMobile" -> "Fitbit"
        else -> pkg
    }

    /** Readable names for common workout types (others are grouped as "другое"). */
    private val EXERCISE_NAMES = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "бег",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "ходьба",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "поход",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "велосипед",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "силовая",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "силовая",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "йога",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "пилатес",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "плавание",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "плавание",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "эллипс",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "гребля",
    )

    data class Result(
        val steps: Map<LocalDate, Double>,      // week start -> average steps per day (days with steps)
        val sleep: Map<LocalDate, Double>,      // week start -> average hours per night (nights with sleep)
        val historyGranted: Boolean,
        val weeksWithoutAccess: Int,
        /** Active calories, workouts and nutrition per week (only where permitted and present). */
        val extra: Map<LocalDate, WeeklyAnalysis.Week> = emptyMap(),
        val missingOptional: Set<String> = emptySet(),
        /** Apps whose sleep was used (package -> nights) and apps whose sleep was ignored. */
        val sleepSources: Map<String, Int> = emptyMap(),
        val ignoredSleepSources: Set<String> = emptySet(),
    )

    suspend fun granted(context: Context): Set<String> =
        if (!HealthConnectSync.isAvailable(context)) emptySet()
        else HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun hasAccess(context: Context): Boolean = granted(context).containsAll(REQUIRED)

    suspend fun read(context: Context, weeks: Int = 26, zone: ZoneId = ZoneId.systemDefault()): Result {
        val client = HealthConnectClient.getOrCreate(context)
        val grantedSet = granted(context)
        val history = grantedSet.contains(HISTORY_PERMISSION)
        val canActive = grantedSet.contains(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))
        val canExercise = grantedSet.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        val canNutrition = grantedSet.contains(HealthPermission.getReadPermission(NutritionRecord::class))
        val extra = mutableMapOf<LocalDate, WeeklyAnalysis.Week>()
        val thisWeek = WeeklyAnalysis.mondayOf(LocalDate.now(zone))
        val steps = mutableMapOf<LocalDate, Double>()
        val sessions = mutableListOf<SleepNights.Session>()
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

                // Sleep: raw sessions; deduplicated per night after the loop (see SleepNights).
                var token: String? = null
                do {
                    val resp = client.readRecords(
                        ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(from, to),
                            pageToken = token,
                        )
                    )
                    for (s in resp.records) sessions += SleepNights.Session(
                        start = s.startTime, end = s.endTime,
                        origin = s.metadata.dataOrigin.packageName,
                        asleepMinutes = s.stages.takeIf { it.isNotEmpty() }?.filter { it.stage !in AWAKE_STAGES }
                            ?.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() },
                    )
                    token = resp.pageToken
                } while (token != null)

                extra[week] = readExtra(client, week, from, to, zone, canActive, canExercise, canNutrition)
            } catch (e: SecurityException) {
                // Older than Health Connect lets us read without the history permission.
                denied++
            } catch (e: Exception) {
                Log.w(TAG, "week $week", e)
            }
        }
        val nights = SleepNights.perNight(sessions.distinctBy { Triple(it.start, it.end, it.origin) }, zone)
        val sleep = nights.hoursByNight.entries
            .groupBy({ WeeklyAnalysis.mondayOf(it.key) }, { it.value })
            .mapValues { (_, h) -> h.average() }
        val missing = buildSet {
            if (!canActive) add("активные калории")
            if (!canExercise) add("тренировки")
            if (!canNutrition) add("питание")
        }
        return Result(steps, sleep, history, denied, extra.filterValues {
            it.workoutKcal != null || it.exercises.isNotEmpty() || it.intakeKcal != null
        }, missing, nights.usedOrigins, nights.ignoredOrigins)
    }

    private suspend fun readExtra(
        client: HealthConnectClient, week: LocalDate, from: LocalDateTime, to: LocalDateTime, zone: ZoneId,
        canActive: Boolean, canExercise: Boolean, canNutrition: Boolean,
    ): WeeklyAnalysis.Week {
        var intake: Double? = null
        var protein: Double? = null
        var daysLogged = 0
        if (canNutrition) {
            val metrics = setOf(NutritionRecord.ENERGY_TOTAL, NutritionRecord.PROTEIN_TOTAL)
            val days = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(metrics, TimeRangeFilter.between(from, to), Period.ofDays(1))
            )
            run {
                val logged = days.filter { (it.result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0) > 0 }
                daysLogged = logged.size
                if (logged.isNotEmpty()) {
                    intake = logged.map { it.result[NutritionRecord.ENERGY_TOTAL]!!.inKilocalories }.average()
                    protein = logged.mapNotNull { it.result[NutritionRecord.PROTEIN_TOTAL]?.inGrams }.takeIf { it.isNotEmpty() }?.average()
                }
            }
        }
        val exercises = mutableListOf<WeeklyAnalysis.Exercise>()
        var workoutKcal: Double? = null
        var kcalSessions = 0
        if (canExercise) {
            val sessions = mutableListOf<ExerciseSessionRecord>()
            var token: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(from, to), pageToken = token)
                )
                sessions += resp.records
                token = resp.pageToken
            } while (token != null)
            // The same workout written by two apps counts once (keep the longer record).
            val kept = mutableListOf<ExerciseSessionRecord>()
            for (e in sessions.sortedByDescending { Duration.between(it.startTime, it.endTime) }) {
                val len = Duration.between(e.startTime, e.endTime).toMinutes()
                val clash = kept.any { k ->
                    val overlap = Duration.between(maxOf(k.startTime, e.startTime), minOf(k.endTime, e.endTime)).toMinutes()
                    overlap > minOf(len, Duration.between(k.startTime, k.endTime).toMinutes()) / 2
                }
                if (!clash) kept += e
            }
            val byType = mutableMapOf<String, Pair<Int, Long>>()
            for (e in kept) {
                val name = EXERCISE_NAMES[e.exerciseType] ?: "другое"
                val (n, min) = byType[name] ?: (0 to 0L)
                byType[name] = (n + 1) to (min + Duration.between(e.startTime, e.endTime).toMinutes())
                // Active calories only inside the workout's own time window — not the whole day's
                // active calories, which some apps also write to Health Connect.
                if (canActive) {
                    val kcal = client.aggregate(
                        AggregateRequest(
                            setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            TimeRangeFilter.between(e.startTime, e.endTime),
                        )
                    )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                    if (kcal != null && kcal > 0) {
                        workoutKcal = (workoutKcal ?: 0.0) + kcal
                        kcalSessions++
                    }
                }
            }
            byType.forEach { (t, v) -> exercises += WeeklyAnalysis.Exercise(t, v.first, v.second.toInt()) }
        }
        return WeeklyAnalysis.Week(
            start = week, workoutKcal = workoutKcal, kcalSessions = kcalSessions, exercises = exercises.sortedByDescending { it.minutes },
            intakeKcal = intake, proteinG = protein, daysLogged = daysLogged,
        )
    }
}
