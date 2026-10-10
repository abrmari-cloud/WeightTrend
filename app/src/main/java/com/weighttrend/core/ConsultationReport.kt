package com.weighttrend.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact plain-text summary to paste into a chat with a nutrition adviser
 * (or Claude): numbers only, with the assumptions behind them stated.
 */
object ConsultationReport {

    private val RU = Locale.forLanguageTag("ru")
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val SHORT = DateTimeFormatter.ofPattern("dd.MM")
    private const val DAY_MS = 86_400_000L

    fun build(
        measurements: List<Measurement>,
        profile: UserProfile?,
        goal: Goal?,
        weeks: List<WeeklyAnalysis.Week>,     // may be empty when Health Connect is not connected
        healthConnectNote: String?,
        nowMs: Long,
        zone: ZoneId,
        weeksShown: Int = 8,
        bodyMeasures: List<BodyMeasure> = emptyList(),
    ): String = buildString {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val sorted = measurements.sortedBy { it.timestampMs }
        val trendVals = Trend.compute(sorted.map { it.timestampMs to it.weightKg })
        val trend = sorted.mapIndexed { i, m -> m.timestampMs to trendVals[i] }

        appendLine("Отчёт о весе и активности · ${DATE.format(today)} (последние $weeksShown недель)")
        appendLine()

        // ---- profile & goal
        if (profile != null) {
            val age = profile.ageAt(today.year, today.monthValue)
            appendLine("Профиль: ${if (profile.isMale) "мужчина" else "женщина"}, $age ${years(age)}, рост ${profile.heightCm.roundToInt()} см.")
        }
        val trendNow = trend.lastOrNull()?.second
        if (goal != null && trendNow != null) {
            val req = Coach.required(trendNow, goal, today)
            append("Цель: ${kg(goal.targetKg)}")
            goal.targetDate?.let { append(" к ${DATE.format(it)}") }
            appendLine(" — нужно ${kcal(req)}/день, темп ${num(Coach.requiredPacePct(trendNow, goal, today))} % веса в неделю.")
        } else if (goal == null) appendLine("Цель не задана.")
        appendLine()

        // ---- weight & balance
        if (trendNow == null) { appendLine("Взвешиваний нет."); return@buildString }
        fun trendChange(days: Int): String {
            val then = trend.lastOrNull { it.first <= nowMs - days * DAY_MS }?.second ?: return "—"
            return signed(trendNow - then)
        }
        val recent = trend.count { it.first >= nowMs - 28 * DAY_MS }
        appendLine("Вес по тренду: ${kg(trendNow)} (за неделю ${trendChange(7)}, за 4 недели ${trendChange(28)}, " +
            "за 12 недель ${trendChange(84)} кг). Взвешиваний за 4 недели: $recent.")
        val est = EnergyBalance.estimate(trend, nowMs)
        if (est != null) {
            appendLine("Энергобаланс за 4 недели: ${kcal(est.kcalPerDay)}/день (${signed(est.kgPerWeek, 2)} кг/нед), " +
                "по наклону тренда × 7700 ккал/кг.")
        }
        val advice = Coach.advise(trend, goal, nowMs, zone)
        appendLine("Оценка приложения: ${advice.headline}. ${advice.text}")
        appendLine()

        // ---- body composition (morning weigh-ins only)
        val morning = sorted.filter { it.fatPercent != null && Conditions.isStandard(it, zone) }
        if (morning.isNotEmpty()) {
            val last = morning.last()
            val fatSeries = Metric.FAT.series(sorted, profile, zone)
            val impSeries = Metric.IMPEDANCE.series(sorted, profile, zone)
            val fatNow = fatSeries.lastOrNull()?.trend ?: last.fatPercent!!
            append("Состав тела (утренние взвешивания; расчёт весов по импедансу, точность ± несколько %): ")
            append("жир ${num(fatNow)} %")
            Metric.delta(fatSeries, 28)?.let { append(" (за 4 нед. ${signed(it)})") }
            last.waterPercent?.let { append(", вода ${num(it)} %") }
            impSeries.lastOrNull()?.let { p ->
                append(", импеданс ${p.trend.roundToInt()} Ом")
                Metric.delta(impSeries, 28)?.let { append(" (${signed(it, 0)})") }
            }
            val lean = last.weightKg * (1 - fatNow / 100)
            append(", безжировая масса ${kg(lean)}")
            appendLine(", базовый обмен (Катч–Макардл) ${int(BodyComposition.bmrKatchMcArdle(last.weightKg, fatNow))} ккал/день.")
            appendLine()
        }

        // ---- tape measurements
        val tape = bodyMeasures.sortedBy { it.date }
        tape.lastOrNull()?.let { last ->
            val prev = tape.getOrNull(tape.size - 2)
            val first = tape.first().takeIf { tape.size > 2 }
            append("Замеры сантиметром, см (последний ${DATE.format(last.date)}")
            prev?.let { append(", в скобках изменение к ${DATE.format(it.date)}") }
            first?.let { append(" / к ${DATE.format(it.date)}") }
            appendLine("):")
            val parts = BodyMeasure.Site.entries.mapNotNull { site ->
                val v = last[site] ?: return@mapNotNull null
                val changes = listOfNotNull(prev?.get(site)?.let { signedCm(v - it) }, first?.get(site)?.let { signedCm(v - it) })
                site.title.lowercase(RU) + " " + cm(v) + (if (changes.isNotEmpty()) " (${changes.joinToString(" / ")})" else "")
            }
            appendLine(parts.joinToString(", ") + ".")
            val extras = buildList {
                BodyMeasure.navySeries(tape, profile).last()?.let { add("жир по формуле Navy ${num(it)} %") }
                last.waistToHeight(profile)?.let { add("талия/рост ${num(it, 2)}") }
                last.waistToHip()?.let { add("талия/бёдра ${num(it, 2)}") }
            }
            if (extras.isNotEmpty()) appendLine(extras.joinToString(", ").replaceFirstChar { it.titlecase(RU) } + ".")
            appendLine()
        }

        // ---- weekly table
        val shown = weeks.filter { !it.start.isAfter(today) }.takeLast(weeksShown)
        if (shown.isNotEmpty()) {
            appendLine("По неделям (с понедельника):")
            appendLine("неделя | вес ср. | баланс ккал/день | шаги/день | сон ч | ккал тренировок за нед. | тренировки | еда ккал/день | белок г/день")
            for (w in shown.asReversed()) {
                appendLine(listOf(
                    SHORT.format(w.start),
                    w.avgWeight?.let { num(it) } ?: "—",
                    w.balanceKcal?.let { kcal(it) } ?: "—",
                    w.avgSteps?.let { int(it) } ?: "—",
                    w.avgSleepHours?.let { num(it) } ?: "—",
                    w.workoutKcal?.let { int(it) } ?: "—",
                    w.exercises.takeIf { it.isNotEmpty() }?.let { ex ->
                        ex.joinToString(", ") { "${it.type} ${it.sessions}× (${it.minutes} мин)" } +
                            (w.kcalPerWorkout?.let { " ≈${int(it)} ккал за тренировку" } ?: "")
                    } ?: "—",
                    w.intakeKcal?.let { "${int(it)} (${w.daysLogged} дн.)" } ?: "—",
                    w.proteinG?.let { int(it) } ?: "—",
                ).joinToString(" | "))
            }
            if (shown.any { it.workoutKcal != null }) {
                appendLine("Калории тренировок — только записанных на часах: Garmin передаёт в Health Connect лишь их. " +
                    "Это нижняя граница: шаги и незаписанные тренировки сюда не входят.")
            }
            appendLine()

            // Real expenditure from logged food and the weight trend.
            val logged = shown.takeLast(4).filter { it.intakeKcal != null && it.daysLogged >= 4 }
            val loggedDays = shown.sumOf { it.daysLogged }
            if (logged.size >= 2 && est != null) {
                val intake = logged.map { it.intakeKcal!! }.average()
                appendLine("Расчётный реальный расход: около ${int(intake - est.kcalPerDay)} ккал/день " +
                    "(средняя записанная еда ${int(intake)} минус энергобаланс ${kcal(est.kcalPerDay)}). " +
                    "Точность зависит от полноты записей.")
            } else {
                appendLine(if (loggedDays == 0) "Питание не записывается (в Health Connect нет данных о еде)."
                else "Питание записано за $loggedDays дн. — мало, чтобы оценить реальный расход.")
            }
        }
        healthConnectNote?.let { appendLine(it) }
        appendLine()
        appendLine("Примечания: энергобаланс оценочный (7700 ккал на кг ткани), знак надёжнее величины; " +
            "дневные колебания веса ±0,5–1 кг — вода. Состав тела — формулы Xiaomi, сравнивать только тренды.")
    }

    private fun years(n: Int) = when {
        n % 100 in 11..14 -> "лет"
        n % 10 == 1 -> "год"
        n % 10 in 2..4 -> "года"
        else -> "лет"
    }
    private fun num(v: Double, digits: Int = 1) = String.format(RU, "%.${digits}f", v)
    private fun kg(v: Double) = num(v) + " кг"
    private fun signedCm(d: Double) = when {
        d > 0.05 -> "+" + cm(d)
        d < -0.05 -> "−" + cm(-d)
        else -> "0"
    }
    private fun cm(v: Double) = if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else num(v)
    private fun int(v: Double) = String.format(RU, "%,d", v.roundToInt())
    private fun kcal(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + "${int(abs(v))} ккал"
    private fun signed(v: Double, digits: Int = 1) =
        (if (v > 0) "+" else if (v < 0) "−" else "±") + num(abs(v), digits)
}
