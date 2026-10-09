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
import com.weighttrend.core.Metric
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
        val withComp = measurements.lastOrNull { it.fatPercent != null }
        if (withComp != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Состав тела · ${Format.dateTime(withComp.timestampMs)}", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth()) {
                        MetricValue("Жир", withComp.fatPercent?.let(Format::pct), Modifier.weight(1f)) { metric = Metric.FAT; selected = null }
                        MetricValue("Вода", withComp.waterPercent?.let(Format::pct), Modifier.weight(1f)) { metric = Metric.WATER; selected = null }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        MetricValue(
                            "Мышцы",
                            withComp.muscleKg?.let { "${Format.kg(it)} · ${Format.pct(it / withComp.weightKg * 100)}" },
                            Modifier.weight(1f),
                        ) { metric = Metric.MUSCLE; selected = null }
                        MetricValue("Кости", withComp.boneKg?.let(Format::kg), Modifier.weight(1f), null)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        MetricValue("ИМТ", Metric.BMI.of(withComp, profile)?.let { Format.num(it) }, Modifier.weight(1f)) {
                            metric = Metric.BMI; selected = null
                        }
                        MetricValue("Висцеральный жир", withComp.visceralFat?.let { Format.num(it, 0) }, Modifier.weight(1f), null)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
