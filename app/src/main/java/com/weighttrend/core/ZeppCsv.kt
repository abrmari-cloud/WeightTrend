package com.weighttrend.core

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Importer for the BODY_*.csv file from a Zepp Life data export:
 *   time,weight,height,bmi,fatRate,bodyWaterRate,boneMass,metabolism,muscleRate,visceralFat
 *   2026-10-08 04:38:28+0000,64.4,170.0,22.2,32.97,47.86,2.58,1181.0,40.58,6.0
 *
 * The export holds every family member's weigh-ins, each tagged with the
 * height of the profile it was assigned to. Only rows of one height are kept:
 * the one closest to [heightCm], or the most frequent one if no height is given.
 *
 * Zepp's "muscleRate" is muscle mass in kg (= weight − fat mass − bone mass).
 */
object ZeppCsv {

    data class Result(
        val measurements: List<Measurement>,
        val keptHeight: Double?,
        val otherPeopleRows: Int,
        val collapsedRepeats: Int,
        val skippedLines: Int,
    )

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")

    /** Repeated readings this close in time and weight are one weigh-in. */
    private const val REPEAT_WINDOW_MS = 10 * 60_000L
    private const val REPEAT_WEIGHT_KG = 0.5

    private data class Row(
        val t: Long, val w: Double, val h: Double?,
        val fat: Double?, val water: Double?, val bone: Double?, val muscle: Double?, val visceral: Double?,
    )

    fun parse(text: String, heightCm: Double?): Result {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return Result(emptyList(), null, 0, 0, 0)
        val header = lines.first().removePrefix("﻿").split(',').map { it.trim() }
        fun col(name: String) = header.indexOf(name)
        val iTime = col("time"); val iW = col("weight"); val iH = col("height")
        val iFat = col("fatRate"); val iWater = col("bodyWaterRate"); val iBone = col("boneMass")
        val iMuscle = col("muscleRate"); val iVisc = col("visceralFat")
        require(iTime >= 0 && iW >= 0) { "Это не файл BODY из экспорта Zepp Life" }

        var skipped = 0
        val rows = mutableListOf<Row>()
        for (line in lines.drop(1)) {
            val p = line.split(',')
            fun num(i: Int): Double? = if (i < 0) null else p.getOrNull(i)?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
            val t = runCatching { OffsetDateTime.parse(p[iTime].trim(), TIME).toInstant().toEpochMilli() }.getOrNull()
            val w = num(iW)
            if (t == null || w == null) { skipped++; continue }
            rows += Row(t, w, num(iH), num(iFat), num(iWater), num(iBone), num(iMuscle), num(iVisc))
        }

        // Pick whose weigh-ins these are.
        val heights = rows.mapNotNull { it.h }.groupingBy { it }.eachCount()
        val kept: Double? = when {
            heights.isEmpty() -> null
            heightCm != null -> heights.keys.minBy { abs(it - heightCm) }
            else -> heights.maxBy { it.value }.key
        }
        val mine = rows.filter { kept == null || it.h == null || it.h == kept }

        // Collapse repeats (e.g. weight-only reading followed by the one with composition).
        val sorted = mine.sortedBy { it.t }
        val out = mutableListOf<Row>()
        var collapsed = 0
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.t - last.t <= REPEAT_WINDOW_MS && abs(r.w - last.w) <= REPEAT_WEIGHT_KG) {
                collapsed++
                // Prefer the reading with body composition; otherwise the later one.
                if (r.fat != null || last.fat == null) out[out.lastIndex] = r
            } else {
                out += r
            }
        }

        return Result(
            measurements = out.map {
                Measurement(
                    timestampMs = it.t,
                    weightKg = it.w,
                    fatPercent = it.fat,
                    waterPercent = it.water,
                    muscleKg = it.muscle,
                    boneKg = it.bone,
                    visceralFat = it.visceral,
                    source = Measurement.Source.ZEPP,
                    scaleKey = "zepp-${it.t}",
                )
            },
            keptHeight = kept,
            otherPeopleRows = rows.size - mine.size,
            collapsedRepeats = collapsed,
            skippedLines = skipped,
        )
    }
}
