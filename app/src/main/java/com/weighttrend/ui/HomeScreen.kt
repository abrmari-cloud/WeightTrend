package com.weighttrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weighttrend.core.BodyComposition
import com.weighttrend.core.Coach
import com.weighttrend.core.Conditions
import com.weighttrend.core.Goal
import com.weighttrend.core.Metric
import java.time.ZoneId
import kotlin.math.roundToInt
import com.weighttrend.core.UserProfile
import kotlinx.coroutines.delay

private enum class Range(val label: String, val days: Long?) {
    M1("Месяц", 30), M3("3 мес", 91), Y1("Год", 365), ALL("Всё", null)
}

@Composable
fun HomeScreen(
    points: List<TrendPoint>,
    live: LiveReading?,
    profile: UserProfile?,
    goal: Goal?,
    scaleBound: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var range by rememberSaveable { mutableStateOf(Range.M3) }
    var metric by rememberSaveable { mutableStateOf(Metric.WEIGHT) }
    var selected by remember { mutableStateOf<Metric.Point?>(null) }

    val measurements = remember(points) { points.map { it.m } }
    val series = remember(measurements, metric, profile) { metric.series(measurements, profile) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!scaleBound || profile == null) {
            SetupCard(scaleBound, profile != null, onOpenSettings)
        }

        LiveCard(live)

        if (measurements.isEmpty()) {
            Text(
                "Пока нет взвешиваний. Встаньте на весы или импортируйте историю в настройках.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        // ----- energy balance & advice -----
        val advice = remember(points, goal) {
            Coach.advise(
                points.map { it.m.timestampMs to it.trend }, goal, System.currentTimeMillis(), ZoneId.systemDefault(),
                weights = points.map { it.m.timestampMs to it.m.weightKg },
            )
        }
        val trendNow = points.lastOrNull()?.trend
        val mood = moodOf(advice, goal, trendNow)
        CoachCard(advice, mood, goal, trendNow, onOpenSettings)

        // ----- metric selector -----
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Metric.entries.forEach { mt ->
                FilterChip(
                    selected = metric == mt,
                    onClick = { metric = mt; selected = null },
                    label = { Text(mt.label) },
                )
            }
        }

        val last = series.lastOrNull()
        if (last == null) {
            Text(
                if (metric == Metric.BMI) "Для ИМТ укажите рост в настройках профиля."
                else "Пока нет взвешиваний с показателем «${metric.label}». Он измеряется, если вставать на весы босиком.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // ----- headline -----
            Column {
                Text("${metric.label} · тренд".uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Format.num(last.trend, metric.digits),
                        style = MaterialTheme.typography.displayLarge.merge(TabularNums),
                        fontSize = 68.sp, lineHeight = 68.sp,
                    )
                    if (metric.unit.isNotEmpty()) {
                        Text(" ${metric.unit}", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
                Text(
                    "Последнее: ${Format.value(last.value, metric)} · ${Format.dateTime(last.m.timestampMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val wantDown = wantsDown(metric, goal, trendNow)
                DeltaCard("за неделю", Metric.delta(series, 7), metric, wantDown, Modifier.weight(1f))
                DeltaCard("за месяц", Metric.delta(series, 30), metric, wantDown, Modifier.weight(1f))
                DeltaCard("за год", Metric.delta(series, 365), metric, wantDown, Modifier.weight(1f))
            }

            // ----- chart -----
            SoftCard {
                Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                    Segmented(Range.entries.map { it.label }, Range.entries.indexOf(range)) { i ->
                        range = Range.entries[i]; selected = null
                    }
                    Spacer(Modifier.height(12.dp))
                    val toMs = System.currentTimeMillis()
                    val fromMs = range.days?.let { toMs - it * 86_400_000L } ?: series.first().m.timestampMs
                    val chartAccent = when (mood) {
                        Mood.GOOD -> moodColor(Mood.GOOD).accent
                        Mood.BAD -> moodColor(Mood.BAD).accent
                        else -> MaterialTheme.colorScheme.primary
                    }
                    TrendChart(
                        series, metric, fromMs, toMs, selected, { selected = it },
                        accent = if (metric == Metric.WEIGHT) chartAccent else MaterialTheme.colorScheme.primary,
                        goal = if (metric == Metric.WEIGHT) goal?.targetKg else null,
                    )
                    val s = selected
                    Text(
                        if (s != null) "${Format.dateTime(s.m.timestampMs)}: ${Format.value(s.value, metric)}, " +
                            "тренд ${Format.num(s.trend, metric.digits)}"
                        else "Точки — взвешивания, линия — тренд. Нажмите на график, чтобы увидеть значение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ----- body composition (tap a value to chart it) -----
        val zone = ZoneId.systemDefault()
        val withComp = measurements.lastOrNull { it.fatPercent != null && Conditions.isStandard(it, zone) }
            ?: measurements.lastOrNull { it.fatPercent != null }
        if (withComp != null) {
            SoftCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Состав тела · ${Format.dateTime(withComp.timestampMs)}", style = MaterialTheme.typography.titleSmall)
                    Conditions.note(withComp, zone)?.let {
                        Text("Утреннего взвешивания с составом тела нет — показано $it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val fat = withComp.fatPercent!!
                    Row(Modifier.fillMaxWidth()) {
                        MetricValue("Жир", Format.pct(fat), Modifier.weight(1f)) { metric = Metric.FAT; selected = null }
                        MetricValue("Вода", withComp.waterPercent?.let(Format::pct), Modifier.weight(1f)) { metric = Metric.WATER; selected = null }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        MetricValue("Безжировая масса", Format.kg(withComp.weightKg * (1 - fat / 100)), Modifier.weight(1f), null)
                        MetricValue("Кости", withComp.boneKg?.let(Format::kg), Modifier.weight(1f), null)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        MetricValue("ИМТ", Metric.BMI.of(withComp, profile)?.let { Format.num(it) }, Modifier.weight(1f)) {
                            metric = Metric.BMI; selected = null
                        }
                        MetricValue("Импеданс", withComp.impedanceOhm?.let { "$it Ом" }, Modifier.weight(1f)) {
                            metric = Metric.IMPEDANCE; selected = null
                        }
                    }
                    MetricValue(
                        "Базовый обмен (по безжировой массе)",
                        "${BodyComposition.bmrKatchMcArdle(withComp.weightKg, fat).roundToInt()} ккал/день",
                        Modifier, null,
                    )
                    Text(
                        "Весы измеряют только вес и импеданс; жир, вода и кости — расчёт по формуле Xiaomi. " +
                            "Сравнивать стоит утренние взвешивания между собой.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** GOOD = keep going (green), CAUTION = needs attention, BAD = moving the wrong way. */
private fun moodOf(advice: Coach.Advice, goal: Goal?, trendKg: Double?): Mood {
    val base = when (advice.kind) {
        Coach.Kind.ON_TRACK, Coach.Kind.REACHED -> Mood.GOOD
        Coach.Kind.TOO_SLOW, Coach.Kind.PLATEAU, Coach.Kind.TOO_FAST -> Mood.CAUTION
        Coach.Kind.WRONG_DIRECTION -> Mood.BAD
        Coach.Kind.NO_DATA, Coach.Kind.NO_GOAL -> Mood.NEUTRAL
    }
    // A fresh turn for the worse dims "good" to "caution", even before the 4-week number reacts.
    return if (base == Mood.GOOD && shiftIsWorse(advice, goal, trendKg) == true) Mood.CAUTION else base
}

/** true = the last 2 weeks moved away from the goal, false = towards it, null = no shift / no goal. */
private fun shiftIsWorse(advice: Coach.Advice, goal: Goal?, trendKg: Double?): Boolean? {
    val shift = advice.shift ?: return null
    if (goal == null || trendKg == null) return null
    val direction = Math.signum(goal.targetKg - trendKg).takeIf { it != 0.0 } ?: return null
    return shift.changeKcal * direction < 0
}

/** For colouring changes: true = lower is better, false = higher is better, null = neutral. */
private fun wantsDown(metric: Metric, goal: Goal?, trendKg: Double?): Boolean? = when (metric) {
    Metric.FAT -> true
    Metric.WEIGHT, Metric.BMI -> if (goal != null && trendKg != null && kotlin.math.abs(goal.targetKg - trendKg) > 0.3)
        goal.targetKg < trendKg else null
    else -> null
}

/** Flat surface with a hairline border instead of a shadow. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) { content() }
}

/** Thin pill-shaped segmented control. */
@Composable
private fun Segmented(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) c.onSurface else c.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CoachCard(advice: Coach.Advice, mood: Mood, goal: Goal?, trendKg: Double?, onOpenSettings: () -> Unit) {
    val mc = moodColor(mood)
    val surface = MaterialTheme.colorScheme.surface
    val fg = mc.onContainer
    val (icon, badge) = when {
        advice.kind == Coach.Kind.REACHED -> Icons.Rounded.Star to "Цель достигнута"
        mood == Mood.GOOD -> Icons.Rounded.ThumbUp to "Так держать"
        mood == Mood.BAD -> Icons.Rounded.Warning to "Не в ту сторону"
        mood == Mood.CAUTION -> Icons.Rounded.Info to "Нужно внимание"
        else -> Icons.Rounded.Info to null
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, mc.accent.copy(alpha = 0.25f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(mc.container, lerp(mc.container, surface, 0.6f))))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(mc.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = mc.accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ЭНЕРГОБАЛАНС · 4 НЕДЕЛИ", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.65f))
                    if (badge != null) Text(badge, style = MaterialTheme.typography.labelLarge, color = mc.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(advice.headline, style = MaterialTheme.typography.headlineSmall.merge(TabularNums), color = fg)
            Text(advice.text, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f))

            if (advice.recent != null || (goal != null && trendKg != null && advice.kind != Coach.Kind.REACHED)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.10f)))
            }
            advice.recent?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("За 2 недели", style = MaterialTheme.typography.bodyMedium, color = fg)
                        Text("быстрее реагирует, но шумнее", style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.6f))
                    }
                    Text(
                        "${Coach.signedKcal(r.kcalPerDay)} ± ${r.errorKcal.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = fg,
                    )
                }
            }
            if (goal != null && trendKg != null && advice.kind != Coach.Kind.REACHED) {
                val left = kotlin.math.abs(trendKg - goal.targetKg)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Цель ${Format.kg(goal.targetKg)}" + (goal.targetDate?.let { " к ${Format.shortDate(it)} ${it.year}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f),
                    )
                    Text("осталось ${Format.kg(left)}", style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = fg)
                }
            }
            advice.shiftNote?.let { note ->
                val worse = shiftIsWorse(advice, goal, trendKg)
                val sc = moodColor(when (worse) { true -> Mood.BAD; false -> Mood.GOOD; null -> Mood.CAUTION })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(surface.copy(alpha = 0.7f))
                        .border(1.dp, sc.accent.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        if (worse == false) Icons.Rounded.ThumbUp else Icons.Rounded.Warning,
                        contentDescription = null, tint = sc.accent, modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (advice.kind == Coach.Kind.NO_GOAL) {
                TextButton(onClick = onOpenSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Задать цель")
                }
            }
        }
    }
}

@Composable
private fun DeltaCard(label: String, delta: Double?, metric: Metric, wantDown: Boolean?, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val good: Boolean? = when {
        delta == null || wantDown == null || kotlin.math.abs(delta) < 0.05 -> null
        else -> (delta < 0) == wantDown
    }
    val color = when (good) { true -> moodColor(Mood.GOOD).accent; false -> moodColor(Mood.BAD).accent; null -> c.onSurface }
    SoftCard(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                delta?.let {
                    val arrow = if (it > 0.05) "↑ " else if (it < -0.05) "↓ " else ""
                    arrow + Format.signed(it).trimStart('+', '−') + if (metric.unit.isNotEmpty()) " ${metric.unit}" else ""
                } ?: "—",
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = color,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetricValue(label: String, value: String?, modifier: Modifier, onClick: (() -> Unit)?) {
    val m = if (onClick != null && value != null) modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick) else modifier
    Column(m) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.titleLarge.merge(TabularNums))
    }
}

@Composable
private fun LiveCard(live: LiveReading?) {
    // Hide the card a few seconds after the last broadcast.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    if (live == null || now - live.atMs > 8_000) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("На весах", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    when {
                        live.hasImpedance -> "Готово, состав тела измерен"
                        live.stable -> "Вес зафиксирован, стойте — измеряю состав тела"
                        else -> "Взвешиваю…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(Format.num(live.weightKg, 2), style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SetupCard(scaleBound: Boolean, hasProfile: Boolean, onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Настройка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (!hasProfile) Text("• Укажите рост, пол и дату рождения: без них не посчитать процент жира.",
                color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
            if (!scaleBound) Text("• Привяжите весы, чтобы взвешивания сохранялись сами.",
                color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
        }
    }
}
