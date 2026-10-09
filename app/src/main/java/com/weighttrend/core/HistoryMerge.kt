package com.weighttrend.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Merges imported history into what the app already has, without duplicates.
 *
 * Two records are the same weigh-in if they are within 10 minutes and 0.5 kg,
 * or on the same local day with (almost) the same weight. Libra, for example,
 * often holds copies of Zepp weigh-ins, sometimes with a placeholder time.
 *
 * When the same weigh-in exists twice, the richer record wins: an incoming
 * record with body composition upgrades an existing one without it, and an
 * imported Libra copy is replaced by the original (exact time, composition).
 */
object HistoryMerge {

    sealed interface Op {
        data class Insert(val m: Measurement) : Op
        data class Replace(val existing: Measurement, val updated: Measurement) : Op
        data class Skip(val m: Measurement) : Op
    }

    private const val WINDOW_MS = 10 * 60_000L
    private const val WINDOW_KG = 0.5
    private const val SAME_DAY_KG = 0.15

    fun sameWeighIn(a: Measurement, b: Measurement, zone: ZoneId): Boolean {
        val dw = abs(a.weightKg - b.weightKg)
        if (abs(a.timestampMs - b.timestampMs) <= WINDOW_MS && dw <= WINDOW_KG) return true
        return dw <= SAME_DAY_KG && day(a, zone) == day(b, zone)
    }

    fun plan(existing: List<Measurement>, incoming: List<Measurement>, zone: ZoneId): List<Op> {
        val working = existing.toMutableList()
        val ops = mutableListOf<Op>()
        for (m in incoming.sortedBy { it.timestampMs }) {
            val idx = working.indexOfFirst { sameWeighIn(it, m, zone) }
            if (idx < 0) {
                ops += Op.Insert(m)
                working += m
                continue
            }
            val old = working[idx]
            val updated = when {
                // A Libra copy is replaced by the original record.
                old.source == Measurement.Source.LIBRA && m.source != Measurement.Source.LIBRA ->
                    m.copy(id = old.id)
                // Add composition the existing record lacks, keep everything else.
                old.fatPercent == null && m.fatPercent != null ->
                    old.copy(
                        fatPercent = m.fatPercent,
                        waterPercent = m.waterPercent,
                        muscleKg = m.muscleKg,
                        boneKg = m.boneKg,
                        visceralFat = m.visceralFat,
                        garminExported = false,
                    )
                else -> null
            }
            if (updated == null) {
                ops += Op.Skip(m)
            } else {
                ops += Op.Replace(old, updated)
                working[idx] = updated
            }
        }
        return ops
    }

    /** Applies [plan] to a plain list (used for building export files outside the app). */
    fun mergedList(existing: List<Measurement>, incoming: List<Measurement>, zone: ZoneId): List<Measurement> {
        val result = existing.toMutableList()
        for (op in plan(existing, incoming, zone)) when (op) {
            is Op.Insert -> result += op.m
            is Op.Replace -> result[result.indexOf(op.existing)] = op.updated
            is Op.Skip -> Unit
        }
        return result.sortedBy { it.timestampMs }
    }

    private fun day(m: Measurement, zone: ZoneId) = Instant.ofEpochMilli(m.timestampMs).atZone(zone).toLocalDate()
}
