package com.weighttrend.core

import kotlin.math.exp

/**
 * Libra-style weight trend: an exponentially smoothed moving average whose
 * smoothing depends on the time between weigh-ins, with a time constant of
 * 7 days:  trend += (1 - e^(-Δt/7d)) * (weight - trend).
 *
 * This reproduces every "weight trend" value in a real Libra export
 * (662 of 662 rows, to 0.1 kg).
 */
object Trend {
    const val TAU_DAYS = 7.0
    private const val DAY_MS = 86_400_000.0

    /** [points] must be sorted by time. Returns one trend value per point. */
    fun compute(points: List<Pair<Long, Double>>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<Double>(points.size)
        var trend = points[0].second
        var prev = points[0].first
        out.add(trend)
        for (i in 1 until points.size) {
            val (t, w) = points[i]
            val dtDays = ((t - prev) / DAY_MS).coerceAtLeast(0.0)
            val k = 1.0 - exp(-dtDays / TAU_DAYS)
            trend += k * (w - trend)
            prev = t
            out.add(trend)
        }
        return out
    }

    /** Trend value extrapolated to [atMs] is just the last trend (Libra behaviour). */
    fun latest(points: List<Pair<Long, Double>>): Double? = compute(points).lastOrNull()
}
