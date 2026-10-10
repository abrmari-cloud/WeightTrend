package com.weighttrend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weighttrend.core.BodyMeasure
import com.weighttrend.core.BodyMeasure.Site
import com.weighttrend.core.Conditions
import com.weighttrend.core.TimeAxis
import com.weighttrend.core.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("ru"))

/** What the chart shows: one tape site or the Navy body-fat estimate. */
private sealed interface Series {
    val title: String
    data class Tape(val site: Site) : Series { override val title get() = site.title }
    data object Navy : Series { override val title = "Жир (Navy)" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MeasuresScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val list by vm.bodyMeasures.collectAsStateCompat()
    val profile by vm.profile.collectAsStateCompat()
    val points by vm.points.collectAsStateCompat()
    var editing by remember { mutableStateOf<BodyMeasure?>(null) }
    var toDelete by remember { mutableStateOf<BodyMeasure?>(null) }
    var seriesKey by rememberSaveable { mutableStateOf("WAIST") }
    val series: Series = if (seriesKey == "NAVY") Series.Navy else Series.Tape(Site.valueOf(seriesKey))
    val zone = ZoneId.systemDefault()

    /** Scale fat % around a date: mean of morning weigh-ins within ±14 days. */
    fun scaleFatNear(d: LocalDate): Double? {
        val from = d.minusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = d.plusDays(15).atStartOfDay(zone).toInstant().toEpochMilli()
        return points.map { it.m }
            .filter { it.timestampMs in from until to && it.fatPercent != null && Conditions.isStandard(it, zone) }
            .mapNotNull { it.fatPercent }.takeIf { it.isNotEmpty() }?.average()
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) {
                IntroCard()
            } else {
                SummaryCard(list, profile, ::scaleFatNear)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = listOf(Site.WAIST, Site.BELLY, Site.HIPS, Site.THIGH, Site.UNDER_CHEST, Site.ARM, Site.NECK)
                        .map { Series.Tape(it) } + Series.Navy
                    options.forEach { s ->
                        val key = if (s is Series.Tape) s.site.name else "NAVY"
                        FilterChip(selected = key == seriesKey, onClick = { seriesKey = key }, label = { Text(s.title) })
                    }
                }
                val navy = BodyMeasure.navySeries(list, profile)
                val chartPoints = list.mapIndexedNotNull { i, b ->
                    val v = when (series) {
                        is Series.Tape -> b[series.site]
                        Series.Navy -> navy[i]
                    } ?: return@mapIndexedNotNull null
                    b.date.atStartOfDay(zone).toInstant().toEpochMilli() to v
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (series is Series.Navy) "Жир по формуле Navy, %" else "${series.title}, см",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (chartPoints.size < 2) {
                            Text(
                                if (series is Series.Navy && profile == null) "Для формулы Navy нужен профиль (рост, пол)."
                                else if (series is Series.Navy) "Нужны шея, талия и ягодицы хотя бы в двух замерах."
                                else "Нужно хотя бы два замера с этим обхватом.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LineChart(chartPoints)
                        }
                    }
                }

                Text("Все замеры", style = MaterialTheme.typography.titleMedium)
                Text("Нажмите, чтобы изменить; долгое нажатие — удалить.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    list.indices.reversed().forEach { i ->
                        val b = list[i]
                        Column(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { editing = b }, onLongClick = { toDelete = b })
                                .padding(vertical = 10.dp),
                        ) {
                            Text(DATE.format(b.date), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                Site.entries.mapNotNull { s -> b[s]?.let { "${s.title.lowercase()} ${cm(it)}" } }.joinToString(" · ") +
                                    (navy[i]?.let { " · жир ≈${Format.num(it)} %" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                HowToMeasure()
            }
            Spacer(Modifier.height(80.dp))
        }
        FloatingActionButton(
            onClick = { editing = BodyMeasure(date = LocalDate.now(), values = emptyMap()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Новый замер") }
    }

    editing?.let { b ->
        EditDialog(
            initial = b,
            previous = list.lastOrNull { it.date < b.date && it.id != b.id } ?: list.lastOrNull { it.id != b.id },
            onSave = { vm.saveBodyMeasure(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить замер?") },
            text = { Text(DATE.format(b.date)) },
            confirmButton = { TextButton(onClick = { vm.deleteBodyMeasure(b.id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun IntroCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Замеры сантиметром", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Талия и живот показывают изменения, которые вес скрывает: при силовых вес может стоять, " +
                    "а объёмы уменьшаться. Достаточно раз в месяц. По шее, талии и ягодицам приложение " +
                    "посчитает процент жира по формуле Navy — независимую проверку данных весов.\n\n" +
                    "Старые замеры тоже можно внести: в форме выберите их дату.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    HowToMeasure()
}

@Composable
private fun HowToMeasure() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Как мерить", style = MaterialTheme.typography.titleSmall)
            Text(
                "Утром натощак, в одно и то же время цикла, если возможно. Лента горизонтально, " +
                    "прилегает, но не врезается. Каждый обхват в одном и том же месте:",
                style = MaterialTheme.typography.bodySmall,
            )
            Site.entries.forEach {
                Text("• ${it.title} — ${it.hint}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Разница меньше 1 см — в пределах точности замера.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SummaryCard(list: List<BodyMeasure>, profile: UserProfile?, scaleFatNear: (LocalDate) -> Double?) {
    val last = list.last()
    val prev = list.getOrNull(list.size - 2)
    val first = list.first().takeIf { list.size > 2 }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Последний замер · ${DATE.format(last.date)}", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            // header
            Row(Modifier.fillMaxWidth()) {
                SumCell("", 1.3f, bold = true)
                SumCell("см", 0.8f, bold = true)
                if (prev != null) SumCell("к ${prev.date.year}", 1f, bold = true)
                if (first != null) SumCell("к ${first.date.year}", 1f, bold = true)
            }
            Site.entries.forEach { s ->
                val v = last[s] ?: return@forEach
                Row(Modifier.fillMaxWidth()) {
                    SumCell(s.title, 1.3f)
                    SumCell(cm(v), 0.8f)
                    if (prev != null) SumCell(prev[s]?.let { delta(v - it) } ?: "—", 1f)
                    if (first != null) SumCell(first[s]?.let { delta(v - it) } ?: "—", 1f)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val lines = buildList {
                BodyMeasure.navySeries(list, profile).last()?.let { navy ->
                    val scale = scaleFatNear(last.date)
                    add("Жир по формуле Navy: ${Format.num(navy)} %" +
                        (scale?.let { " (весы в те же дни: ${Format.num(it)} %)" } ?: ""))
                }
                last.waistToHeight(profile)?.let {
                    add("Талия/рост: ${Format.num(it, 2)} — " + if (it < 0.5) "ниже порога 0,5, норма" else "выше порога 0,5")
                }
                last.waistToHip()?.let {
                    val limit = if (profile?.isMale == true) 0.90 else 0.85
                    add("Талия/бёдра: ${Format.num(it, 2)} — " +
                        if (it < limit) "ниже порога ВОЗ ${Format.num(limit, 2)}" else "выше порога ВОЗ ${Format.num(limit, 2)}")
                }
            }
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (profile == null && last.waistToHip() == null) {
                Text("Заполните профиль, чтобы считать жир и отношения.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SumCell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text, Modifier.weight(weight),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDialog(
    initial: BodyMeasure,
    previous: BodyMeasure?,
    onSave: (BodyMeasure) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(initial.date) }
    var pickingDate by remember { mutableStateOf(false) }
    val texts = remember {
        mutableStateMapOf<Site, String>().apply { Site.entries.forEach { s -> put(s, initial[s]?.let { cm(it) } ?: "") } }
    }
    fun parse(t: String) = t.replace(',', '.').trim().toDoubleOrNull()
    val parsed = Site.entries.associateWith { parse(texts[it].orEmpty()) }
    val invalid = Site.entries.filter { s ->
        texts[s].orEmpty().isNotBlank() && parsed[s]?.let { it !in BodyMeasure.MIN_CM..BodyMeasure.MAX_CM } != false
    }
    val values = parsed.filterValues { it != null && it in BodyMeasure.MIN_CM..BodyMeasure.MAX_CM }.mapValues { it.value!! }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый замер" else "Изменить замер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingDate = true }) { Text("Дата: ${DATE.format(date)}") }
                Text("Заполните то, что измерили; пустые поля можно оставить.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Site.entries.forEach { s ->
                    OutlinedTextField(
                        value = texts[s].orEmpty(),
                        onValueChange = { texts[s] = it },
                        label = { Text("${s.title}, см") },
                        supportingText = previous?.get(s)?.let { p -> @Composable { Text("было ${cm(p)} (${DATE.format(previous.date)})") } },
                        isError = s in invalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = values.isNotEmpty() && invalid.isEmpty(),
                onClick = { onSave(initial.copy(date = date, values = values)) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
}

/** Minimal line chart for sparse values (a handful of points over months or years). */
@Composable
private fun LineChart(points: List<Pair<Long, Double>>) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 11.sp, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 36.dp.toPx()
        val right = 16.dp.toPx()
        val bottom = 22.dp.toPx()
        val top = 18.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val fromMs = points.first().first
        val toMs = points.last().first.coerceAtLeast(fromMs + 1)
        val rawLo = points.minOf { it.second }
        val rawHi = points.maxOf { it.second }
        val step = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0).first { (rawHi - rawLo).coerceAtLeast(1.0) / it <= 5 }
        val lo = floor((rawLo - step / 2) / step) * step
        val hi = ceil((rawHi + step / 2) / step) * step
        fun x(ms: Long) = left + w * (ms - fromMs).toFloat() / (toMs - fromMs).toFloat()
        fun y(v: Double) = top + h * (1f - ((v - lo) / (hi - lo)).toFloat())

        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        var g = lo
        while (g <= hi + 1e-9) {
            drawLine(colors.outlineVariant, Offset(left, y(g)), Offset(size.width - right, y(g)), 1f, pathEffect = dash)
            val t = measurer.measure(Format.num(g, if (step < 1) 1 else 0), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(g) - t.size.height / 2f))
            g += step
        }
        var lastRight = Float.NEGATIVE_INFINITY
        for (tick in TimeAxis.ticks(fromMs, toMs, ZoneId.systemDefault(), 4)) {
            val t = measurer.measure(tick.label, labelStyle)
            val lx = (x(tick.epochMs) - t.size.width / 2f).coerceIn(left, size.width - t.size.width)
            if (lx > lastRight + 4.dp.toPx()) {
                drawText(t, topLeft = Offset(lx, size.height - t.size.height))
                lastRight = lx + t.size.width
            }
        }
        val path = Path()
        points.forEachIndexed { i, (ms, v) -> if (i == 0) path.moveTo(x(ms), y(v)) else path.lineTo(x(ms), y(v)) }
        drawPath(path, colors.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEach { (ms, v) ->
            drawCircle(colors.primary, 4.dp.toPx(), Offset(x(ms), y(v)))
            val t = measurer.measure(cm(v), valueStyle)
            drawText(t, topLeft = Offset(
                (x(ms) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width),
                y(v) - t.size.height - 4.dp.toPx(),
            ))
        }
    }
}

private fun cm(v: Double): String = if (abs(v - Math.round(v)) < 0.05) Math.round(v).toString() else Format.num(v)
private fun delta(d: Double): String = when {
    d > 0.05 -> "+" + cm(d)
    d < -0.05 -> "−" + cm(-d)
    else -> "0"
}
