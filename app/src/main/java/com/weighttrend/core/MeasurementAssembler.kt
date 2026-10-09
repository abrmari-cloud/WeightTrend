package com.weighttrend.core

import kotlin.math.abs

/**
 * Turns the stream of repeated scale advertisements into saved measurements.
 *
 * The scale broadcasts the same result many times: first a stabilized weight,
 * then (if bare feet) the same reading with impedance. Each reading carries the
 * scale's own timestamp, which identifies the weigh-in.
 */
object MeasurementAssembler {

    sealed interface Action {
        data object Ignore : Action
        data class Insert(val measurement: Measurement) : Action
        data class AddImpedance(val existing: Measurement, val updated: Measurement) : Action
    }

    /** Readings closer than this with near-equal weight are the same weigh-in. */
    const val MERGE_WINDOW_MS = 3 * 60_000L

    fun decide(
        frame: MiScaleFrame,
        receivedAtMs: Long,
        recent: List<Measurement>,
        composition: ((weightKg: Double, impedance: Int) -> BodyComposition.Result)?,
    ): Action {
        if (!frame.isFinal) return Action.Ignore
        val weight = frame.weightKg.round2()

        val same = recent.firstOrNull { it.source == Measurement.Source.SCALE && it.scaleKey == frame.scaleKey }
            ?: recent.firstOrNull {
                it.source == Measurement.Source.SCALE &&
                    abs(it.timestampMs - receivedAtMs) <= MERGE_WINDOW_MS &&
                    abs(it.weightKg - weight) <= 0.2
            }

        if (same != null) {
            val imp = frame.impedanceOhm
            if (imp != null && same.impedanceOhm == null) {
                return Action.AddImpedance(same, withComposition(same.copy(impedanceOhm = imp), composition))
            }
            return Action.Ignore
        }

        val m = Measurement(
            timestampMs = receivedAtMs,
            weightKg = weight,
            impedanceOhm = frame.impedanceOhm,
            source = Measurement.Source.SCALE,
            scaleKey = frame.scaleKey,
        )
        return Action.Insert(withComposition(m, composition))
    }

    fun withComposition(
        m: Measurement,
        composition: ((Double, Int) -> BodyComposition.Result)?,
    ): Measurement {
        val imp = m.impedanceOhm ?: return m
        val r = composition?.invoke(m.weightKg, imp) ?: return m
        return m.copy(
            fatPercent = r.fatPercent,
            waterPercent = r.waterPercent,
            muscleKg = r.muscleKg,
            boneKg = r.boneKg,
            visceralFat = r.visceralFat,
            healthConnectSynced = false,
            garminExported = false,
        )
    }
}
