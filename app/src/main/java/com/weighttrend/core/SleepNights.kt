package com.weighttrend.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns Health Connect sleep sessions into hours per night.
 *
 * Health Connect does not deduplicate sessions: when Garmin and, say, the
 * phone's own sleep detection both write the same night, summing them gives
 * 15–16 hours. So per night only one app is used (Garmin if present, otherwise
 * the app with the most sleep that night), overlapping sessions of that app are
 * counted once, short daytime naps are skipped, and awake stages are excluded
 * when the app provides stages (Garmin's "sleep duration" works the same way).
 */
object SleepNights {

    const val GARMIN = "com.garmin.android.apps.connectmobile"

    data class Session(
        val start: Instant,
        val end: Instant,
        val origin: String,
        /** Minutes actually asleep from stage data; null when the app wrote no stages. */
        val asleepMinutes: Long? = null,
    ) {
        val minutes: Long get() = Duration.between(start, end).toMinutes()
        val sleptMinutes: Long get() = asleepMinutes ?: minutes
    }

    data class Result(
        val hoursByNight: Map<LocalDate, Double>,
        /** Apps that wrote sleep, with how many nights each was the one used. */
        val usedOrigins: Map<String, Int>,
        val ignoredOrigins: Set<String>,
    )

    fun perNight(sessions: List<Session>, zone: ZoneId, preferred: String = GARMIN): Result {
        val used = mutableMapOf<String, Int>()
        val all = sessions.map { it.origin }.toSet()
        val hours = sessions
            .filterNot { isNap(it, zone) }
            .groupBy { it.end.atZone(zone).toLocalDate() }
            .mapNotNull { (night, list) ->
                val origin = if (list.any { it.origin == preferred }) preferred
                else list.groupBy { it.origin }.maxBy { (_, s) -> s.sumOf { it.minutes } }.key
                val minutes = withoutOverlaps(list.filter { it.origin == origin }).sumOf { it.sleptMinutes }
                if (minutes <= 0) return@mapNotNull null
                used[origin] = (used[origin] ?: 0) + 1
                night to minutes / 60.0
            }.toMap()
        return Result(hours, used, all - used.keys)
    }

    /** Short sessions that end in the afternoon or evening are naps, not the night's sleep. */
    private fun isNap(s: Session, zone: ZoneId): Boolean {
        val endHour = s.end.atZone(zone).hour
        return s.minutes < 180 && endHour in 12..21
    }

    /** Keeps the longer of two sessions that overlap by more than half of the shorter one. */
    private fun withoutOverlaps(list: List<Session>): List<Session> {
        val kept = mutableListOf<Session>()
        for (s in list.sortedByDescending { it.minutes }) {
            val clash = kept.any { k ->
                val overlap = Duration.between(maxOf(k.start, s.start), minOf(k.end, s.end)).toMinutes()
                overlap > minOf(k.minutes, s.minutes) / 2
            }
            if (!clash) kept += s
        }
        return kept
    }
}
