package com.weighttrend.core

import java.time.Instant
import java.time.ZoneId

/** A value that can be charted over time, with its own Libra-style trend. */
enum class Metric(val label: String, val unit: String, val digits: Int, val bodyComposition: Boolean) {
    WEIGHT("Вес", "кг", 1, false),
    FAT("Жир", "%", 1, true),
    WATER("Вода", "%", 1, true),
    IMPEDANCE("Импеданс", "Ом", 0, true),
    BMI("ИМТ", "", 1, false);

    /** Value of this metric for one weigh-in, or null if it was not measured. */
    fun of(m: Measurement, profile: UserProfile?): Double? = when (this) {
        WEIGHT -> m.weightKg
        FAT -> m.fatPercent
        WATER -> m.waterPercent
        IMPEDANCE -> m.impedanceOhm?.toDouble()
        BMI -> profile?.heightCm?.takeIf { it > 0 }?.let { h -> m.weightKg / ((h / 100.0) * (h / 100.0)) }
    }

    data class Point(val m: Measurement, val value: Double, val trend: Double)

    /**
     * All weigh-ins that have this metric, each with the metric's trend at that
     * moment. Body-composition metrics skip non-standard weigh-ins (see
     * [Conditions]), because impedance depends strongly on the time of day.
     */
    fun series(measurements: List<Measurement>, profile: UserProfile?, zone: ZoneId = ZoneId.systemDefault()): List<Point> {
        val pts = measurements
            .filter { !bodyComposition || Conditions.isStandard(it, zone) }
            .mapNotNull { m -> of(m, profile)?.let { m to it } }
        val trend = Trend.compute(pts.map { it.first.timestampMs to it.second })
        return pts.mapIndexed { i, (m, v) -> Point(m, v, trend[i]) }
    }

    companion object {
        /** Change of the trend over [days]: latest trend vs the trend at the last point before that. */
        fun delta(series: List<Point>, days: Long): Double? {
            val last = series.lastOrNull() ?: return null
            val cutoff = last.m.timestampMs - days * 86_400_000L
            val then = series.lastOrNull { it.m.timestampMs <= cutoff } ?: return null
            return last.trend - then.trend
        }
    }
}

/**
 * Body-composition readings are comparable only under similar conditions.
 * "Standard" = a morning weigh-in (04:00–10:59 local time), when the body's
 * water balance is most stable. Other weigh-ins still count for weight.
 */
object Conditions {
    const val MORNING_FROM_HOUR = 4
    const val MORNING_TO_HOUR = 11

    fun isStandard(m: Measurement, zone: ZoneId): Boolean {
        val h = Instant.ofEpochMilli(m.timestampMs).atZone(zone).hour
        return h in MORNING_FROM_HOUR until MORNING_TO_HOUR
    }

    /** Short reason a weigh-in is excluded from body-composition trends, or null. */
    fun note(m: Measurement, zone: ZoneId): String? =
        if (m.fatPercent == null || isStandard(m, zone)) null
        else "не утром — не в тренде состава тела"
}
