package com.weighttrend.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Daily energy balance estimated from the weight:
 *   kcal/day ≈ slope (kg/day) × 7700 kcal/kg.
 *
 * The slope is a least-squares fit of the RAW weigh-ins (averaged per day),
 * not of the smoothed trend: the trend already lags about a week behind, and
 * fitting it would add that lag on top of the window.
 *
 * Two windows:
 *  - 4 weeks: reliable (± ~100 kcal/day at daily weigh-ins), slow to react;
 *  - 2 weeks: reacts to a change of habits within days, but ± ~250 kcal/day.
 * [shift] compares the last 2 weeks with the 2 weeks before them, so a change
 * of regime shows up without waiting for the 4-week window to roll over.
 *
 * 7700 kcal is the energy in a kilogram of body fat; for mixed tissue the true
 * value is lower, so treat the result as an order of magnitude with a reliable sign.
 */
object EnergyBalance {
    const val KCAL_PER_KG = 7700.0
    const val WINDOW_DAYS = 28
    const val MIN_SPAN_DAYS = 14
    const val MIN_WEIGH_INS = 6

    const val SHORT_WINDOW_DAYS = 14
    const val SHORT_MIN_SPAN_DAYS = 9
    const val SHORT_MIN_WEIGH_INS = 5

    /** Day-to-day scatter assumed at least this large (kg), so a lucky straight run is not over-trusted. */
    const val MIN_NOISE_KG = 0.3
    /** A shift smaller than this is not worth mentioning even if it is "significant". */
    const val MIN_SHIFT_KCAL = 150.0

    private const val DAY_MS = 86_400_000.0

    data class Estimate(
        /** Negative = deficit (losing), positive = surplus (gaining). */
        val kcalPerDay: Double,
        val kgPerWeek: Double,
        val spanDays: Int,
        /** Number of days with a weigh-in in the window. */
        val weighIns: Int,
        /** Standard error of [kcalPerDay]: the true value is within ±2 × this in ~95 % of cases. */
        val errorKcal: Double,
    )

    data class Shift(
        val recent: Estimate,     // last 2 weeks
        val before: Estimate,     // the 2 weeks before that
    ) {
        /** Positive = balance got worse (more surplus / less deficit). */
        val changeKcal: Double get() = recent.kcalPerDay - before.kcalPerDay
        val error: Double get() = sqrt(recent.errorKcal * recent.errorKcal + before.errorKcal * before.errorKcal)
        val significant: Boolean get() = abs(changeKcal) >= MIN_SHIFT_KCAL && abs(changeKcal) > 2 * error
    }

    /**
     * [weights] = (time, kg) of raw weigh-ins, any order. Several weigh-ins on one
     * day are averaged so that a busy day does not count more than a quiet one.
     */
    fun estimate(
        weights: List<Pair<Long, Double>>,
        nowMs: Long,
        windowDays: Int = WINDOW_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
        minSpanDays: Int = if (windowDays >= WINDOW_DAYS) MIN_SPAN_DAYS else SHORT_MIN_SPAN_DAYS,
        minWeighIns: Int = if (windowDays >= WINDOW_DAYS) MIN_WEIGH_INS else SHORT_MIN_WEIGH_INS,
    ): Estimate? {
        val from = nowMs - windowDays * DAY_MS.toLong()
        val days = weights.filter { it.first in from..nowMs }
            .groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
            .map { (d, pts) -> d to pts.map { it.second }.average() }
            .sortedBy { it.first }
        if (days.size < minWeighIns) return null
        val first: LocalDate = days.first().first
        val xy = days.map { (d, kg) -> (d.toEpochDay() - first.toEpochDay()).toDouble() to kg }
        val span = xy.last().first
        if (span < minSpanDays) return null
        val slopePerDay = slope(xy)
        return Estimate(
            kcalPerDay = slopePerDay * KCAL_PER_KG,
            kgPerWeek = slopePerDay * 7,
            spanDays = span.toInt(),
            weighIns = days.size,
            errorKcal = slopeError(xy, slopePerDay) * KCAL_PER_KG,
        )
    }

    /** Last 2 weeks against the 2 weeks before; null if either half has too few weigh-ins. */
    fun shift(weights: List<Pair<Long, Double>>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Shift? {
        val recent = estimate(weights, nowMs, SHORT_WINDOW_DAYS, zone) ?: return null
        val before = estimate(weights, nowMs - SHORT_WINDOW_DAYS * DAY_MS.toLong(), SHORT_WINDOW_DAYS, zone) ?: return null
        return Shift(recent, before)
    }

    /** Standard error of the OLS slope, with the scatter floored at [MIN_NOISE_KG]. */
    fun slopeError(xy: List<Pair<Double, Double>>, slope: Double): Double {
        val n = xy.size
        if (n < 3) return Double.POSITIVE_INFINITY
        val mx = xy.sumOf { it.first } / n
        val my = xy.sumOf { it.second } / n
        val sxx = xy.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx == 0.0) return Double.POSITIVE_INFINITY
        val ssr = xy.sumOf { val r = it.second - (my + slope * (it.first - mx)); r * r }
        val sigma = max(sqrt(ssr / (n - 2)), MIN_NOISE_KG)
        return sigma / sqrt(sxx)
    }

    /** Ordinary least-squares slope of y over x. */
    fun slope(xy: List<Pair<Double, Double>>): Double {
        val n = xy.size
        if (n < 2) return 0.0
        val mx = xy.sumOf { it.first } / n
        val my = xy.sumOf { it.second } / n
        val sxx = xy.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx == 0.0) return 0.0
        return xy.sumOf { (it.first - mx) * (it.second - my) } / sxx
    }

    /** Net walking cost above rest, ≈ 0.5 kcal per kg body weight per km. */
    fun walkingKm(kcal: Double, weightKg: Double): Double = kcal / (0.5 * weightKg)

    /** Typical step count for a distance (≈ 1300 steps/km at an average stride). */
    fun steps(km: Double): Int = (km * 1300).toInt()
}
