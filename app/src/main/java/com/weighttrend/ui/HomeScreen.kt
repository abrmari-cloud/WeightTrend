package com.weighttrend.ui

import androidx.compose.foundation.clickable
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
            Coach.advise(points.map { it.m.timestampMs to it.trend }, goal, System.currentTimeMillis(), ZoneId.systemDefault())
        }
        CoachCard(advice, goal, points.lastOrNull()?.trend, onOpenSettings)

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
                Text("${metric.label} · тренд", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.num(last.trend, metric.digits), fontSize = 64.sp, fontWeight = FontWeight.SemiBold, lineHeight = 64.sp)
                    if (metric.unit.isNotEmpty()) {
                        Text(" ${metric.unit}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                Text(
                    "Последнее: ${Format.value(last.value, metric)} · ${Format.dateTime(last.m.timestampMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeltaCard("за неделю", Metric.delta(series, 7), metric, Modifier.weight(1f))
                DeltaCard("за месяц", Metric.delta(series, 30), metric, Modifier.weight(1f))
                DeltaCard("за год", Metric.delta(series, 365), metric, Modifier.weight(1f))
            }

            // ----- chart -----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Range.entries.forEach { r ->
                            FilterChip(selected = range == r, onClick = { range = r; selected = null }, label = { Text(r.label) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val toMs = System.currentTimeMillis()
                    val fromMs = range.days?.let { toMs - it * 86_400_000L } ?: series.first().m.timestampMs
                    TrendChart(series, metric, fromMs, toMs, selected, { selected = it })
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun CoachCard(advice: Coach.Advice, goal: Goal?, trendKg: Double?, onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (advice.kind) {
        Coach.Kind.ON_TRACK, Coach.Kind.REACHED -> colors.primaryContainer to colors.onPrimaryContainer
        Coach.Kind.TOO_FAST, Coach.Kind.WRONG_DIRECTION -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Энергобаланс за 4 недели", style = MaterialTheme.typography.labelMedium, color = fg)
            Text(advice.headline, style = MaterialTheme.typography.titleMedium, color = fg)
            Text(advice.text, style = MaterialTheme.typography.bodyMedium, color = fg)
            if (goal != null && trendKg != null && advice.kind != Coach.Kind.REACHED) {
                val left = trendKg - goal.targetKg
                Text(
                    "Цель ${Format.kg(goal.targetKg)}" +
                        (goal.targetDate?.let { " к ${Format.shortDate(it)} ${it.year}" } ?: "") +
                        " · осталось ${Format.kg(kotlin.math.abs(left))}",
                    style = MaterialTheme.typography.bodySmall, color = fg,
                )
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
private fun DeltaCard(label: String, delta: Double?, metric: Metric, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                delta?.let { Format.signed(it) + if (metric.unit.isNotEmpty()) " ${metric.unit}" else "" } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun MetricValue(label: String, value: String?, modifier: Modifier, onClick: (() -> Unit)?) {
    val m = if (onClick != null && value != null) modifier.clickable(onClick = onClick) else modifier
    Column(m) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.titleLarge)
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
