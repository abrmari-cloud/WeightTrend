/*
 * Body-composition formulas for the Xiaomi Mi Body Composition Scale.
 *
 * Ported from openScale (MiScaleLib.kt), Copyright (C) 2025 olie.xdev,
 * which is based on https://github.com/prototux/MIBCS-reverse-engineering.
 * Licensed under the GNU General Public License v3 or later; see LICENSE.
 */
package com.weighttrend.core

class BodyComposition(private val profile: UserProfile, private val age: Int) {

    private val sex = if (profile.isMale) 1 else 0
    private val height = profile.heightCm.toFloat()

    data class Result(
        val fatPercent: Double,
        val waterPercent: Double,
        val muscleKg: Double,
        val boneKg: Double,
        /** Null when the formula gives an implausible value (it does for many women). */
        val visceralFat: Double?,
    )

    fun compute(weightKg: Double, impedanceOhm: Int): Result {
        val w = weightKg.toFloat()
        val imp = impedanceOhm.toFloat()
        val fat = bodyFat(w, imp)
        return Result(
            fatPercent = fat.toDouble().round1(),
            waterPercent = water(w, imp).toDouble().round1(),
            // Same definition as Zepp Life's "muscle": weight − fat mass − bone mass.
            muscleKg = lbm(w, imp).toDouble().round1(),
            boneKg = boneMass(w, imp).toDouble().round1(),
            visceralFat = visceralFat(w).toDouble().round1().takeIf { it in 1.0..59.0 },
        )
    }

    private fun lbmCoefficient(weight: Float, impedance: Float): Float {
        var lbm = (height * 9.058f / 100.0f) * (height / 100.0f)
        lbm += weight * 0.32f + 12.226f
        lbm -= impedance * 0.0068f
        lbm -= age * 0.0542f
        return lbm
    }

    fun bmi(weightKg: Double): Double =
        (weightKg / ((height / 100.0) * (height / 100.0))).round1()

    private fun lbm(weight: Float, impedance: Float): Float {
        var lean = weight - ((bodyFat(weight, impedance) * 0.01f) * weight) - boneMass(weight, impedance)
        if (sex == 0 && lean >= 84.0f) lean = 120.0f
        else if (sex == 1 && lean >= 93.5f) lean = 120.0f
        return lean
    }

    private fun water(weight: Float, impedance: Float): Float {
        val water = (100.0f - bodyFat(weight, impedance)) * 0.7f
        val coeff = if (water < 50f) 1.02f else 0.98f
        return coeff * water
    }

    private fun boneMass(weight: Float, impedance: Float): Float {
        val base = if (sex == 0) 0.245691014f else 0.18016894f
        var bone = (base - (lbmCoefficient(weight, impedance) * 0.05158f)) * -1.0f
        bone = if (bone > 2.2f) bone + 0.1f else bone - 0.1f
        if (sex == 0 && bone > 5.1f) bone = 8.0f
        else if (sex == 1 && bone > 5.2f) bone = 8.0f
        return bone
    }

    private fun visceralFat(weight: Float): Float {
        return if (sex == 0) {
            if (weight > (13.0f - (height * 0.5f)) * -1.0f) {
                val subsub = ((height * 1.45f) + (height * 0.1158f) * height) - 120.0f
                val sub = weight * 500.0f / subsub
                (sub - 6.0f) + (age * 0.07f)
            } else {
                val sub = 0.691f + (height * -0.0024f) + (height * -0.0024f)
                (((height * 0.027f) - (sub * weight)) * -1.0f) + (age * 0.07f) - age
            }
        } else {
            if (height < weight * 1.6f) {
                val sub = ((height * 0.4f) - (height * (height * 0.0826f))) * -1.0f
                ((weight * 305.0f) / (sub + 48.0f)) - 2.9f + (age * 0.15f)
            } else {
                val sub = 0.765f + height * -0.0015f
                (((height * 0.143f) - (weight * sub)) * -1.0f) + (age * 0.15f) - 5.0f
            }
        }
    }

    private fun bodyFat(weight: Float, impedance: Float): Float {
        var lbmSub = 0.8f
        if (sex == 0 && age <= 49) lbmSub = 9.25f
        else if (sex == 0 && age > 49) lbmSub = 7.25f

        val lbmCoeff = lbmCoefficient(weight, impedance)
        var coeff = 1.0f
        if (sex == 1 && weight < 61.0f) {
            coeff = 0.98f
        } else if (sex == 0 && weight > 60.0f) {
            coeff = 0.96f
            if (height > 160.0f) coeff *= 1.03f
        } else if (sex == 0 && weight < 50.0f) {
            coeff = 1.02f
            if (height > 160.0f) coeff *= 1.03f
        }
        var fat = (1.0f - (((lbmCoeff - lbmSub) * coeff) / weight)) * 100.0f
        if (fat > 63.0f) fat = 75.0f
        return fat
    }
}

internal fun Double.round1(): Double = Math.round(this * 10.0) / 10.0
internal fun Double.round2(): Double = Math.round(this * 100.0) / 100.0
