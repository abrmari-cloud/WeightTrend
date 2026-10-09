package com.weighttrend.core

/**
 * One weigh-in. Body-composition fields are null when the scale did not report
 * impedance (e.g. measured in socks) or when the source had no such data.
 *
 * Units: weight/bone/muscle in kg, fat/water in percent of body weight.
 */
data class Measurement(
    val id: Long = 0,
    val timestampMs: Long,
    val weightKg: Double,
    val impedanceOhm: Int? = null,
    val fatPercent: Double? = null,
    val waterPercent: Double? = null,
    val muscleKg: Double? = null,
    val boneKg: Double? = null,
    val visceralFat: Double? = null,
    val source: Source = Source.SCALE,
    /** Scale-side identity of the measurement, used to drop repeated advertisements. */
    val scaleKey: String? = null,
    val healthConnectSynced: Boolean = false,
    val garminExported: Boolean = false,
) {
    enum class Source { SCALE, LIBRA, MANUAL, ZEPP }
}

/** Profile data needed by the body-composition formulas. */
data class UserProfile(
    val isMale: Boolean,
    val birthYear: Int,
    val birthMonth: Int,
    val heightCm: Double,
) {
    fun ageAt(year: Int, month: Int): Int {
        var age = year - birthYear
        if (month < birthMonth) age -= 1
        return age.coerceAtLeast(1)
    }
}
