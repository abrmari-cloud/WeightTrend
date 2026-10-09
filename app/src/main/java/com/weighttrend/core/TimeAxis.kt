package com.weighttrend.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Picks "round" date ticks for a chart's time axis: days, weeks (Mondays),
 * months (the 1st) or years (1 January), whichever gives at most [maxTicks]
 * labels for the visible range.
 */
object TimeAxis {

    data class Tick(val epochMs: Long, val label: String)

    private val RU: Locale = Locale.forLanguageTag("ru")
    private val DAY = DateTimeFormatter.ofPattern("d MMM", RU)
    private val MONTH = DateTimeFormatter.ofPattern("LLL", RU)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("LLL yy", RU)
    private val YEAR = DateTimeFormatter.ofPattern("yyyy", RU)

    private enum class Unit { DAY, WEEK, MONTH, YEAR }
    private data class Step(val unit: Unit, val n: Int)

    private val STEPS = listOf(
        Step(Unit.DAY, 1), Step(Unit.DAY, 2), Step(Unit.WEEK, 1), Step(Unit.WEEK, 2),
        Step(Unit.MONTH, 1), Step(Unit.MONTH, 2), Step(Unit.MONTH, 3), Step(Unit.MONTH, 6),
        Step(Unit.YEAR, 1), Step(Unit.YEAR, 2), Step(Unit.YEAR, 5),
    )

    fun ticks(fromMs: Long, toMs: Long, zone: ZoneId, maxTicks: Int): List<Tick> {
        if (toMs <= fromMs || maxTicks < 1) return emptyList()
        val from = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        for (step in STEPS) {
            val dates = generate(from, to, step)
            if (dates.size <= maxTicks) return label(dates, step, from, to, zone, fromMs, toMs)
        }
        val last = STEPS.last()
        return label(generate(from, to, last), last, from, to, zone, fromMs, toMs)
    }

    private fun generate(from: LocalDate, to: LocalDate, step: Step): List<LocalDate> {
        var d = when (step.unit) {
            Unit.DAY -> from
            Unit.WEEK -> from.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            Unit.MONTH -> {
                val first = from.withDayOfMonth(1).let { if (it < from) it.plusMonths(1) else it }
                // align to multiples of n months (Jan, Apr, Jul, Oct for n = 3)
                var m = first
                while ((m.monthValue - 1) % step.n != 0) m = m.plusMonths(1)
                m
            }
            Unit.YEAR -> {
                var y = from.withDayOfYear(1).let { if (it < from) it.plusYears(1) else it }
                while (y.year % step.n != 0) y = y.plusYears(1)
                y
            }
        }
        val out = mutableListOf<LocalDate>()
        var guard = 0
        while (d <= to && guard++ < 1000) {
            out += d
            d = when (step.unit) {
                Unit.DAY -> d.plusDays(step.n.toLong())
                Unit.WEEK -> d.plusWeeks(step.n.toLong())
                Unit.MONTH -> d.plusMonths(step.n.toLong())
                Unit.YEAR -> d.plusYears(step.n.toLong())
            }
        }
        return out
    }

    private fun label(
        dates: List<LocalDate>, step: Step, from: LocalDate, to: LocalDate,
        zone: ZoneId, fromMs: Long, toMs: Long,
    ): List<Tick> {
        val multiYear = from.year != to.year
        return dates.mapNotNull { d ->
            val ms = d.atStartOfDay(zone).toInstant().toEpochMilli()
            if (ms < fromMs || ms > toMs) return@mapNotNull null
            val text = when (step.unit) {
                Unit.DAY, Unit.WEEK -> DAY.format(d)
                Unit.MONTH -> if (multiYear && (d.monthValue == 1 || d == dates.first())) MONTH_YEAR.format(d) else MONTH.format(d)
                Unit.YEAR -> YEAR.format(d)
            }
            Tick(ms, text.replace(".", ""))
        }
    }
}
