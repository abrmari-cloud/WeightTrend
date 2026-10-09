package com.weighttrend.ui

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
    var selected by remember { mutableStateOf<TrendPoint?>(null) }

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

        val last = points.lastOrNull()
        if (last == null) {
            Text(
                "Пока нет взвешиваний. Встаньте на весы или импортируйте историю из Libra в настройках.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        // ----- headline -----
        Column {
            Text("Тренд", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.num(last.trend), fontSize = 64.sp, fontWeight = FontWeight.SemiBold, lineHeight = 64.sp)
                Text(" кг", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(
                "Последнее: ${Format.kg(last.m.weightKg)} · ${Format.dateTime(last.m.timestampMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeltaCard("за неделю", trendDelta(points, 7), Modifier.weight(1f))
            DeltaCard("за месяц", trendDelta(points, 30), Modifier.weight(1f))
            DeltaCard("за год", trendDelta(points, 365), Modifier.weight(1f))
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
                val fromMs = range.days?.let { toMs - it * 86_400_000L } ?: points.first().m.timestampMs
                TrendChart(points, fromMs, toMs, selected, { selected = it })
                val s = selected
                Text(
                    if (s != null) "${Format.dateTime(s.m.timestampMs)}: ${Format.kg(s.m.weightKg)}, тренд ${Format.num(s.trend)}" +
                        (s.m.fatPercent?.let { ", жир ${Format.pct(it)}" } ?: "")
                    else "Точки — взвешивания, линия — тренд. Нажмите на график, чтобы увидеть значение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ----- body composition -----
        val withComp = points.lastOrNull { it.m.fatPercent != null }
        if (withComp != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Состав тела · ${Format.dateTime(withComp.m.timestampMs)}", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Жир", withComp.m.fatPercent?.let(Format::pct), Modifier.weight(1f))
                        Metric("Вода", withComp.m.waterPercent?.let(Format::pct), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Metric("Мышцы", withComp.m.muscleKg?.let(Format::kg), Modifier.weight(1f))
                        Metric("Кости", withComp.m.boneKg?.let(Format::kg), Modifier.weight(1f))
                    }
                    if (profile != null) {
                        Metric("ИМТ", Format.num(BodyComposition(profile, 30).bmi(last.m.weightKg)), Modifier)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Change of the trend over [days]: now vs the trend at the last point before that. */
private fun trendDelta(points: List<TrendPoint>, days: Long): Double? {
    val last = points.lastOrNull() ?: return null
    val cutoff = last.m.timestampMs - days * 86_400_000L
    val then = points.lastOrNull { it.m.timestampMs <= cutoff } ?: return null
    return last.trend - then.trend
}

@Composable
private fun DeltaCard(label: String, delta: Double?, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(delta?.let { Format.signed(it) + " кг" } ?: "—", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun Metric(label: String, value: String?, modifier: Modifier) {
    Column(modifier) {
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
