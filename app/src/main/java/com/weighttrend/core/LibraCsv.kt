package com.weighttrend.core

import java.time.Instant
import java.time.ZoneId

/**
 * Importer for Libra's CSV export (format version 6):
 *   #date;weight;weight trend;body fat;body fat trend;muscle mass;muscle mass trend;log
 *
 * Older Libra databases store most weigh-ins twice: once as a placeholder at
 * local midnight and once with the real time, both with the same weight.
 * Within one local day, entries with an identical weight are collapsed,
 * keeping the one with a real (non-midnight) time.
 */
object LibraCsv {

    private val LIBRA_TIME = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    data class Result(val measurements: List<Measurement>, val rawRows: Int, val skippedLines: Int)

    fun parse(text: String, zone: ZoneId): Result {
        var skipped = 0
        var lbs = false
        val rows = mutableListOf<Triple<Instant, Double, Double?>>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#Units:", ignoreCase = true) && line.contains("lb", ignoreCase = true)) lbs = true
                continue
            }
            val p = line.split(';')
            if (p.size < 2) { skipped++; continue }
            val instant = runCatching { Instant.parse(p[0].trim()) }.getOrNull()
            var weight = p[1].trim().replace(',', '.').toDoubleOrNull()
            if (instant == null || weight == null || weight <= 0.0) { skipped++; continue }
            if (lbs) weight *= 0.45359237
            val fat = p.getOrNull(3)?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 }
            rows += Triple(instant, weight, fat)
        }

        // Group by local calendar day, collapse identical weights within the day.
        val byDay = rows.sortedBy { it.first }.groupBy { it.first.atZone(zone).toLocalDate() }
        val out = mutableListOf<Measurement>()
        for ((_, entries) in byDay) {
            val kept = LinkedHashMap<Double, Triple<Instant, Double, Double?>>()
            for (e in entries) {
                val key = e.second.round2()
                val existing = kept[key]
                if (existing == null) {
                    kept[key] = e
                } else if (isLocalMidnight(existing.first, zone) && !isLocalMidnight(e.first, zone)) {
                    kept[key] = e.copy(third = e.third ?: existing.third)
                }
            }
            kept.values.forEach { (t, w, fat) ->
                out += Measurement(
                    timestampMs = t.toEpochMilli(),
                    weightKg = w.round2(),
                    fatPercent = fat,
                    source = Measurement.Source.LIBRA,
                    scaleKey = "libra-${t.toEpochMilli()}",
                )
            }
        }
        return Result(out.sortedBy { it.timestampMs }, rows.size, skipped)
    }

    /** Backup in Libra's own format, so the data can always go back into Libra. */
    fun write(measurements: List<Measurement>): String {
        val sorted = measurements.sortedBy { it.timestampMs }
        val trend = Trend.compute(sorted.map { it.timestampMs to it.weightKg })
        fun f(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: ""
        return buildString {
            append("#Version: 6\n#Units: kg\n\n")
            append("#date;weight;weight trend;body fat;body fat trend;muscle mass;muscle mass trend;log\n")
            sorted.forEachIndexed { i, m ->
                append(LIBRA_TIME.format(Instant.ofEpochMilli(m.timestampMs))).append(';')
                append(String.format(java.util.Locale.US, "%.2f", m.weightKg)).append(';')
                append(f(trend[i])).append(';')
                append(f(m.fatPercent)).append(";;")
                append(f(m.muscleKg)).append(";;\n")
            }
        }
    }

    private fun isLocalMidnight(t: Instant, zone: ZoneId): Boolean {
        val z = t.atZone(zone)
        return z.hour == 0 && z.minute == 0 && z.second == 0
    }
}
