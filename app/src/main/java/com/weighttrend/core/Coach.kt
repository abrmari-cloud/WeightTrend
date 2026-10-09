package com.weighttrend.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Weight goal: target trend weight and (optionally) the date to reach it. */
data class Goal(val targetKg: Double, val targetDate: LocalDate?)

/**
 * Rule-based comment on the energy balance relative to the goal.
 * Every verdict is explainable from the numbers it shows.
 */
object Coach {

    enum class Kind { NO_DATA, NO_GOAL, REACHED, ON_TRACK, TOO_SLOW, TOO_FAST, WRONG_DIRECTION, PLATEAU }

    data class Advice(
        val kind: Kind,
        val headline: String,
        val text: String,
        /** Daily balance needed for the goal (negative = deficit). */
        val requiredKcal: Double?,
        /** When the goal is reached at the current pace, if moving towards it. */
        val forecast: LocalDate?,
    )

    /** Recommended pace when no date is set: 0.5 % of body weight per week. */
    const val DEFAULT_PACE_PCT = 0.5
    /** Faster than this risks losing muscle. */
    const val MAX_PACE_PCT = 1.0
    /** Within this many kcal/day of the required balance counts as on track. */
    const val TOLERANCE_KCAL = 100.0

    private val RU = Locale.forLanguageTag("ru")
    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

    /** Required balance (kcal/day) to go from [trendKg] to the goal by its date or at the default pace. */
    fun required(trendKg: Double, goal: Goal, today: LocalDate): Double {
        val needKg = goal.targetKg - trendKg
        val days = goal.targetDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }?.takeIf { it > 0 }
        return if (days != null) needKg * EnergyBalance.KCAL_PER_KG / days
        else Math.signum(needKg) * trendKg * DEFAULT_PACE_PCT / 100 * EnergyBalance.KCAL_PER_KG / 7
    }

    /** Pace (% of body weight per week) the goal needs; > [MAX_PACE_PCT] is not advisable. */
    fun requiredPacePct(trendKg: Double, goal: Goal, today: LocalDate): Double =
        abs(required(trendKg, goal, today)) * 7 / EnergyBalance.KCAL_PER_KG / trendKg * 100

    /** Earliest date the goal is reachable at the maximum advisable pace. */
    fun earliestSafeDate(trendKg: Double, goal: Goal, today: LocalDate): LocalDate {
        val kgPerWeek = trendKg * MAX_PACE_PCT / 100
        val weeks = abs(goal.targetKg - trendKg) / kgPerWeek
        return today.plusDays(ceil(weeks * 7).toLong())
    }

    fun advise(
        trend: List<Pair<Long, Double>>,
        goal: Goal?,
        nowMs: Long,
        zone: ZoneId,
    ): Advice {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val est = EnergyBalance.estimate(trend, nowMs)
        val trendNow = trend.lastOrNull()?.second

        if (est == null || trendNow == null) {
            val n = trend.count { it.first >= nowMs - EnergyBalance.WINDOW_DAYS * 86_400_000L }
            return Advice(
                Kind.NO_DATA, "Мало данных",
                "За последние 4 недели взвешиваний: $n. Для оценки нужно хотя бы ${EnergyBalance.MIN_WEIGH_INS} " +
                    "за 2 недели — взвешивайтесь через день или чаще.",
                null, null,
            )
        }

        val balance = est.kcalPerDay
        val balanceText = signedKcal(balance)
        val paceText = "${signedKg(est.kgPerWeek)} кг/нед"

        if (goal == null) {
            val state = when {
                balance > TOLERANCE_KCAL -> "вес медленно растёт"
                balance < -TOLERANCE_KCAL -> "вес снижается"
                else -> "вес стабилен"
            }
            return Advice(Kind.NO_GOAL, "$balanceText в день",
                "Темп $paceText: $state. Задайте цель в настройках — появятся подсказки, что менять.", null, null)
        }

        val needKg = goal.targetKg - trendNow
        if (abs(needKg) <= 0.3) {
            val drift = when {
                balance > TOLERANCE_KCAL -> " Но вес понемногу растёт ($balanceText в день) — следите, чтобы не уйти выше."
                balance < -TOLERANCE_KCAL -> " Вес продолжает снижаться ($balanceText в день) — можно есть чуть больше."
                else -> " Баланс близок к нулю — так и держите."
            }
            return Advice(Kind.REACHED, "Цель достигнута",
                "Тренд ${num(trendNow)} кг, цель ${num(goal.targetKg)} кг.$drift", 0.0, null)
        }

        // A deadline that needs more than the safe pace is planned at the safe pace instead.
        val rawReq = required(trendNow, goal, today)
        val maxKcal = trendNow * MAX_PACE_PCT / 100 * EnergyBalance.KCAL_PER_KG / 7
        val capped = abs(rawReq) > maxKcal
        val req = if (capped) Math.signum(rawReq) * maxKcal else rawReq
        val capNote = if (capped) " Срок цели слишком короткий: считаю по безопасному темпу ${num(MAX_PACE_PCT)} % веса в неделю " +
            "(цель к ${DATE.format(earliestSafeDate(trendNow, goal, today))})." else ""
        val direction = Math.signum(needKg)               // −1 = losing, +1 = gaining
        val movingRight = balance * direction > 0
        val forecast = if (movingRight && abs(est.kgPerWeek) > 0.02) {
            today.plusDays((abs(needKg) / abs(est.kgPerWeek) * 7).roundToInt().toLong())
        } else null
        val pacePct = abs(est.kgPerWeek) / trendNow * 100
        val weight = trendNow

        // Too fast (only meaningful when moving the right way).
        if (movingRight && pacePct > MAX_PACE_PCT) {
            val excess = abs(balance) - trendNow * MAX_PACE_PCT / 100 * EnergyBalance.KCAL_PER_KG / 7
            return Advice(Kind.TOO_FAST, "$balanceText в день — слишком быстро",
                "Темп ${num(pacePct)} % веса в неделю, больше рекомендуемого ${num(MAX_PACE_PCT)} %. " +
                    "Растёт риск терять мышцы. Добавьте около ${excess.roundToInt()} ккал в день, в первую очередь белком.",
                req, forecast)
        }

        // Plateau: stalled now, but clearly moving towards the goal in the 4 weeks before.
        val earlier = EnergyBalance.estimate(trend, nowMs - EnergyBalance.WINDOW_DAYS * 86_400_000L)
        if (abs(est.kgPerWeek) < 0.05 && earlier != null && earlier.kgPerWeek * direction > 0.15) {
            val gap = abs(req - balance)
            return Advice(Kind.PLATEAU, "Плато",
                "Последние 4 недели вес почти не меняется, хотя до этого шёл к цели. Это нормально: вместе с весом " +
                    "снизился и расход. Чтобы двигаться дальше, нужно ещё около ${gap.roundToInt()} ккал в день — ${equivalent(gap, weight)}.$capNote",
                req, null)
        }

        if (!movingRight && abs(balance) > TOLERANCE_KCAL / 2) {
            val change = abs(req - balance)
            val what = if (direction < 0) "растёт" else "снижается"
            return Advice(Kind.WRONG_DIRECTION, "$balanceText в день — не в ту сторону",
                "Вес медленно $what ($paceText). Чтобы выйти на цель, баланс нужно сдвинуть примерно на " +
                    "${change.roundToInt()} ккал в день — ${equivalent(change, weight)}.$capNote",
                req, null)
        }

        val gap = (req - balance) * direction               // > 0: not enough progress
        val dateText = forecast?.let { " Прогноз: цель к ${DATE.format(it)}." } ?: ""
        return if (gap <= TOLERANCE_KCAL) {
            Advice(Kind.ON_TRACK, "$balanceText в день — в коридоре",
                "Темп $paceText, для цели нужно ${signedKcal(req)} в день.$dateText Ничего не меняйте.$capNote",
                req, forecast)
        } else {
            Advice(Kind.TOO_SLOW, "$balanceText в день — не хватает",
                "Для цели нужно ${signedKcal(req)} в день. Не хватает около ${gap.roundToInt()} ккал — " +
                    "${equivalent(gap, weight)}.$dateText$capNote",
                req, forecast)
        }
    }

    /** Translates a daily kcal gap into walking and food, split half/half when it is large. */
    fun equivalent(kcal: Double, weightKg: Double): String {
        fun walk(k: Double): String {
            val km = EnergyBalance.walkingKm(k, weightKg)
            val steps = ((EnergyBalance.steps(km) + 250) / 500) * 500
            return "${num(km)} км ходьбы, около ${String.format(RU, "%,d", steps)} шагов"
        }
        fun food(k: Double) = when {
            k <= 120 -> "ложка масла или ломтик хлеба"
            k <= 250 -> "перекус или десерт"
            else -> "небольшой приём пищи"
        }
        return if (kcal <= 250) "это примерно ${walk(kcal)} или ${food(kcal)}"
        else "например, половину едой (−${(kcal / 2).roundToInt()} ккал, ${food(kcal / 2)}) " +
            "и половину движением (${walk(kcal / 2)})"
    }

    private fun num(v: Double) = String.format(RU, "%.1f", v)
    private fun signedKcal(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + "${abs(v).roundToInt()} ккал"
    private fun signedKg(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(RU, "%.2f", abs(v))
}
