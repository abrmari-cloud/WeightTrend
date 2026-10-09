package com.weighttrend.core

/** A value that can be charted over time, with its own Libra-style trend. */
enum class Metric(val label: String, val unit: String, val digits: Int) {
    WEIGHT("Вес", "кг", 1),
    FAT("Жир", "%", 1),
    WATER("Вода", "%", 1),
    MUSCLE("Мышцы", "%", 1),
    BMI("ИМТ", "", 1);

    /** Value of this metric for one weigh-in, or null if it was not measured. */
    fun of(m: Measurement, profile: UserProfile?): Double? = when (this) {
        WEIGHT -> m.weightKg
        FAT -> m.fatPercent
        WATER -> m.waterPercent
        MUSCLE -> m.muscleKg?.let { it / m.weightKg * 100.0 }
        BMI -> profile?.heightCm?.takeIf { it > 0 }?.let { h -> m.weightKg / ((h / 100.0) * (h / 100.0)) }
    }

    data class Point(val m: Measurement, val value: Double, val trend: Double)

    /** All weigh-ins that have this metric, each with the metric's trend at that moment. */
    fun series(measurements: List<Measurement>, profile: UserProfile?): List<Point> {
        val pts = measurements.mapNotNull { m -> of(m, profile)?.let { m to it } }
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
