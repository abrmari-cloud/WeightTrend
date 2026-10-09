package com.weighttrend.core

/**
 * Daily energy balance estimated from the weight trend:
 *   kcal/day ≈ trend slope (kg/day) × 7700 kcal/kg.
 *
 * The slope is a least-squares fit of the trend over the last [WINDOW_DAYS],
 * so a single unusual weigh-in barely moves it. 7700 kcal is the energy in a
 * kilogram of body fat; for mixed tissue the true value is lower, so treat
 * the result as an order of magnitude with a reliable sign.
 */
object EnergyBalance {
    const val KCAL_PER_KG = 7700.0
    const val WINDOW_DAYS = 28
    const val MIN_SPAN_DAYS = 14
    const val MIN_WEIGH_INS = 6
    private const val DAY_MS = 86_400_000.0

    data class Estimate(
        /** Negative = deficit (losing), positive = surplus (gaining). */
        val kcalPerDay: Double,
        val kgPerWeek: Double,
        val spanDays: Int,
        val weighIns: Int,
    )

    /** [trend] = (time, trend value) for each weigh-in, sorted by time. */
    fun estimate(trend: List<Pair<Long, Double>>, nowMs: Long, windowDays: Int = WINDOW_DAYS): Estimate? {
        val from = nowMs - windowDays * DAY_MS.toLong()
        val pts = trend.filter { it.first in from..nowMs }
        if (pts.size < MIN_WEIGH_INS) return null
        val span = (pts.last().first - pts.first().first) / DAY_MS
        if (span < MIN_SPAN_DAYS) return null
        val slopePerDay = slope(pts.map { (it.first - pts.first().first) / DAY_MS to it.second })
        return Estimate(
            kcalPerDay = slopePerDay * KCAL_PER_KG,
            kgPerWeek = slopePerDay * 7,
            spanDays = span.toInt(),
            weighIns = pts.size,
        )
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
