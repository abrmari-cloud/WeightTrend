package com.weighttrend.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val RU: Locale = Locale.forLanguageTag("ru")
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", RU)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", RU)
    private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", RU)
    private val time = DateTimeFormatter.ofPattern("HH:mm", RU)

    fun num(v: Double, digits: Int = 1): String = String.format(RU, "%.${digits}f", v)
    /** Whole number with Russian digit grouping: 8 597. */
    fun int(v: Double): String = String.format(RU, "%,d", Math.round(v))
    fun kg(v: Double): String = num(v) + " кг"
    fun pct(v: Double): String = num(v) + " %"
    fun value(v: Double, metric: com.weighttrend.core.Metric): String =
        num(v, metric.digits) + if (metric.unit.isNotEmpty()) " ${metric.unit}" else ""

    fun signed(v: Double): String = (if (v > 0.05) "+" else if (v < -0.05) "−" else "±") + num(kotlin.math.abs(v))

    fun date(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when {
            d == today -> "сегодня"
            d == today.minusDays(1) -> "вчера"
            d.year == today.year -> dayMonth.format(d)
            else -> dayMonthYear.format(d)
        }
    }

    fun time(ms: Long): String = time.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun dateTime(ms: Long): String = date(ms) + ", " + time(ms)
    fun month(d: LocalDate): String = monthYear.format(d).replaceFirstChar { it.titlecase(RU) }
    fun shortDate(d: LocalDate): String = dayMonth.format(d)
}
