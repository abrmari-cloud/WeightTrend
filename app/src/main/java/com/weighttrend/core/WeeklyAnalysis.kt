package com.weighttrend.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Week-by-week comparison of activity and sleep with the energy balance.
 * Daily correlations drown in water-weight noise; weekly averages do not.
 */
object WeeklyAnalysis {

    data class Week(
        val start: LocalDate,              // Monday
        val avgSteps: Double? = null,
        val avgSleepHours: Double? = null,
        /** Average weight of the week's weigh-ins. */
        val avgWeight: Double? = null,
        /** Energy balance from neighbouring weekly average weights; null without them. */
        val balanceKcal: Double? = null,
        val activeKcal: Double? = null,    // average per day
        val exercises: List<Exercise> = emptyList(),
        val intakeKcal: Double? = null,    // average per logged day
        val proteinG: Double? = null,      // average per logged day
        val daysLogged: Int = 0,
    )

    data class Exercise(val type: String, val sessions: Int, val minutes: Int)

    data class Split(
        val threshold: Double,
        val highWeeks: Int, val highBalance: Double,
        val lowWeeks: Int, val lowBalance: Double,
        /** Standard error of [difference]. */
        val standardError: Double,
        val minValue: Double, val maxValue: Double,
    ) {
        /** How much better (more negative) the balance is in "high" weeks. */
        val difference: Double get() = highBalance - lowBalance
        /** True when the difference is clearly larger than week-to-week noise (~2 standard errors). */
        val significant: Boolean get() = abs(difference) > 2 * standardError
        /** True when the factor barely varied (max/min within 25 %), so there is little to compare. */
        val narrowRange: Boolean get() = minValue > 0 && (maxValue - minValue) / minValue < 0.25
    }

    const val MIN_WEEKS = 6

    fun mondayOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Average weight per week. */
    fun weeklyWeights(weights: List<Pair<Long, Double>>, zone: ZoneId): Map<LocalDate, Double> =
        weights.groupBy { mondayOf(Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate()) }
            .mapValues { (_, pts) -> pts.map { it.second }.average() }

    /**
     * Energy balance per week from weekly average weights: the change between
     * the weeks before and after (over 14 days), or, for the latest week,
     * between it and the week before (over 7 days). Averages smooth out most
     * of the day-to-day water noise that two single readings would carry.
     */
    fun balances(weights: List<Pair<Long, Double>>, zone: ZoneId): Map<LocalDate, Double> {
        val avg = weeklyWeights(weights, zone)
        return avg.keys.mapNotNull { w ->
            val prev = avg[w.minusWeeks(1)]
            val next = avg[w.plusWeeks(1)]
            val kgPerDay = when {
                prev != null && next != null -> (next - prev) / 14.0
                prev != null -> (avg.getValue(w) - prev) / 7.0
                else -> return@mapNotNull null
            }
            w to kgPerDay * EnergyBalance.KCAL_PER_KG
        }.toMap()
    }

    fun weeks(
        weights: List<Pair<Long, Double>>,   // raw weigh-ins (time, kg)
        steps: Map<LocalDate, Double>,
        sleep: Map<LocalDate, Double>,
        zone: ZoneId,
        extra: Map<LocalDate, Week> = emptyMap(),   // active kcal, exercise, nutrition
    ): List<Week> {
        val bal = balances(weights, zone)
        val avg = weeklyWeights(weights, zone)
        val all = (steps.keys + sleep.keys + bal.keys + avg.keys + extra.keys).toSortedSet()
        return all.map { w ->
            val e = extra[w]
            Week(
                start = w, avgSteps = steps[w], avgSleepHours = sleep[w], avgWeight = avg[w], balanceKcal = bal[w],
                activeKcal = e?.activeKcal, exercises = e?.exercises ?: emptyList(),
                intakeKcal = e?.intakeKcal, proteinG = e?.proteinG, daysLogged = e?.daysLogged ?: 0,
            )
        }
    }

    /** Splits weeks at the median of [value]; null if there are too few weeks with both numbers. */
    fun split(weeks: List<Week>, value: (Week) -> Double?): Split? {
        val pairs = weeks.mapNotNull { w -> val v = value(w); val b = w.balanceKcal; if (v != null && b != null) v to b else null }
        if (pairs.size < MIN_WEEKS) return null
        val sorted = pairs.map { it.first }.sorted()
        val median = sorted[sorted.size / 2]
        val high = pairs.filter { it.first >= median }.map { it.second }
        val low = pairs.filter { it.first < median }.map { it.second }
        if (high.size < 2 || low.size < 2) return null
        fun variance(x: List<Double>): Double { val m = x.average(); return x.sumOf { (it - m) * (it - m) } / (x.size - 1) }
        return Split(
            threshold = median,
            highWeeks = high.size, highBalance = high.average(),
            lowWeeks = low.size, lowBalance = low.average(),
            standardError = sqrt(variance(high) / high.size + variance(low) / low.size),
            minValue = sorted.first(), maxValue = sorted.last(),
        )
    }
}
