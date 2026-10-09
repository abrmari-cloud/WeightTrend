package com.weighttrend.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Week-by-week comparison of activity and sleep with the energy balance.
 * Daily correlations drown in water-weight noise; weekly averages do not.
 */
object WeeklyAnalysis {

    data class Week(
        val start: LocalDate,              // Monday
        val avgSteps: Double?,
        val avgSleepHours: Double?,
        /** From the weight trend over the week; null with fewer than 2 weigh-ins. */
        val balanceKcal: Double?,
    )

    data class Split(
        val threshold: Double,
        val highWeeks: Int, val highBalance: Double,
        val lowWeeks: Int, val lowBalance: Double,
    ) {
        /** How much better (more negative) the balance is in "high" weeks. */
        val difference: Double get() = highBalance - lowBalance
    }

    const val MIN_WEEKS = 6

    fun mondayOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Balance per week from the trend at the first and last weigh-in of that week. */
    fun balances(trend: List<Pair<Long, Double>>, zone: ZoneId): Map<LocalDate, Double> {
        val byWeek = trend.groupBy { mondayOf(Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate()) }
        return byWeek.mapNotNull { (week, pts) ->
            if (pts.size < 2) return@mapNotNull null
            val days = (pts.last().first - pts.first().first) / 86_400_000.0
            if (days < 3) return@mapNotNull null
            week to (pts.last().second - pts.first().second) / days * EnergyBalance.KCAL_PER_KG
        }.toMap()
    }

    fun weeks(
        trend: List<Pair<Long, Double>>,
        steps: Map<LocalDate, Double>,       // week start -> average daily steps
        sleep: Map<LocalDate, Double>,       // week start -> average hours per night
        zone: ZoneId,
    ): List<Week> {
        val bal = balances(trend, zone)
        val all = (steps.keys + sleep.keys + bal.keys).toSortedSet()
        return all.map { Week(it, steps[it], sleep[it], bal[it]) }
    }

    /** Splits weeks at the median of [value]; null if there are too few weeks with both numbers. */
    fun split(weeks: List<Week>, value: (Week) -> Double?): Split? {
        val pairs = weeks.mapNotNull { w -> val v = value(w); val b = w.balanceKcal; if (v != null && b != null) v to b else null }
        if (pairs.size < MIN_WEEKS) return null
        val sorted = pairs.map { it.first }.sorted()
        val median = sorted[sorted.size / 2]
        val high = pairs.filter { it.first >= median }
        val low = pairs.filter { it.first < median }
        if (high.isEmpty() || low.isEmpty()) return null
        return Split(
            threshold = median,
            highWeeks = high.size, highBalance = high.map { it.second }.average(),
            lowWeeks = low.size, lowBalance = low.map { it.second }.average(),
        )
    }
}
