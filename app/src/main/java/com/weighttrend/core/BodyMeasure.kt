package com.weighttrend.core

import java.time.LocalDate
import kotlin.math.log10

/**
 * Tape-measure circumferences taken on one day (cm). Any site may be missing.
 * Sites follow the usual home-measurement sheet.
 */
data class BodyMeasure(
    val id: Long = 0,
    val date: LocalDate,
    val values: Map<Site, Double>,
) {
    enum class Site(val title: String, val hint: String, val column: String) {
        NECK("Шея", "под кадыком, в самом узком месте", "neck"),
        ARM("Рука", "бицепс, в самом широком месте, рука расслаблена", "arm"),
        UNDER_CHEST("Под грудью", "сразу под грудью", "under_chest"),
        WAIST("Талия", "в самом узком месте, на выдохе", "waist"),
        BELLY("Живот", "по пупку, живот не втягивать", "belly"),
        HIPS("Ягодицы", "в самом широком месте", "hips"),
        THIGH("Бедро", "середина бедра", "thigh"),
    }

    operator fun get(s: Site): Double? = values[s]

    /** Body fat by the U.S. Navy tape formula; null without neck, waist (and hips for women). */
    fun navyFatPercent(profile: UserProfile?): Double? =
        profile?.let { BodyMeasure.navyFatPercent(it.isMale, it.heightCm, this[Site.NECK], this[Site.WAIST], this[Site.HIPS]) }

    /** Waist-to-height ratio; under 0.5 is the usual healthy threshold. */
    fun waistToHeight(profile: UserProfile?): Double? {
        val w = this[Site.WAIST] ?: return null
        return profile?.heightCm?.takeIf { it > 0 }?.let { w / it }
    }

    /** Waist-to-hip ratio (WHO risk threshold: 0.85 for women, 0.90 for men). */
    fun waistToHip(): Double? {
        val w = this[Site.WAIST] ?: return null
        val h = this[Site.HIPS]?.takeIf { it > 0 } ?: return null
        return w / h
    }

    companion object {
        const val MIN_CM = 10.0
        const val MAX_CM = 250.0

        /**
         * U.S. Navy formula (Hodgdon & Beckett), metric version.
         * Women: 495 / (1.29579 − 0.35004·log10(waist + hip − neck) + 0.22100·log10(height)) − 450
         * Men:   495 / (1.0324 − 0.19077·log10(waist − neck) + 0.15456·log10(height)) − 450
         */
        fun navyFatPercent(isMale: Boolean, heightCm: Double, neck: Double?, waist: Double?, hips: Double?): Double? {
            if (neck == null || waist == null || heightCm <= 0) return null
            val density = if (isMale) {
                val d = waist - neck
                if (d <= 0) return null
                1.0324 - 0.19077 * log10(d) + 0.15456 * log10(heightCm)
            } else {
                val d = waist + (hips ?: return null) - neck
                if (d <= 0) return null
                1.29579 - 0.35004 * log10(d) + 0.22100 * log10(heightCm)
            }
            return (495 / density - 450).takeIf { it in 2.0..70.0 }
        }
    }
}
