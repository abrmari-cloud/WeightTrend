package com.weighttrend.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrend.core.Conditions
import com.weighttrend.core.Measurement
import java.time.Instant
import java.time.ZoneId

private sealed interface HistoryRow {
    data class Header(val title: String) : HistoryRow
    data class Item(val p: TrendPoint) : HistoryRow
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    points: List<TrendPoint>,
    onDelete: (Long) -> Unit,
    onAdd: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var toDelete by remember { mutableStateOf<TrendPoint?>(null) }
    var adding by remember { mutableStateOf(false) }

    val rows = remember(points) {
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<HistoryRow>()
        var lastMonth: java.time.YearMonth? = null
        for (p in points.asReversed()) {
            val d = Instant.ofEpochMilli(p.m.timestampMs).atZone(zone).toLocalDate()
            val ym = java.time.YearMonth.from(d)
            if (ym != lastMonth) { out += HistoryRow.Header(Format.month(d)); lastMonth = ym }
            out += HistoryRow.Item(p)
        }
        out
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            items(rows, key = { r -> if (r is HistoryRow.Item) "i${r.p.m.id}" else "h${(r as HistoryRow.Header).title}" }) { r ->
                when (r) {
                    is HistoryRow.Header -> Text(
                        r.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
                    )
                    is HistoryRow.Item -> {
                        val m = r.p.m
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = {}, onLongClick = { toDelete = r.p })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(Format.dateTime(m.timestampMs), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append("тренд ").append(Format.num(r.p.trend))
                                        m.fatPercent?.let { append(" · жир ").append(Format.pct(it)) }
                                        if (m.source != Measurement.Source.SCALE) append(" · ").append(sourceLabel(m.source))
                                        Conditions.note(m, java.time.ZoneId.systemDefault())?.let { append(" · ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(Format.kg(m.weightKg), style = MaterialTheme.typography.titleMedium)
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Добавить вручную") }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить взвешивание?") },
            text = { Text("${Format.dateTime(p.m.timestampMs)}, ${Format.kg(p.m.weightKg)}") },
            confirmButton = { TextButton(onClick = { onDelete(p.m.id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..300.0 }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Вес вручную") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("кг") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(enabled = value != null, onClick = { value?.let(onAdd); adding = false }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}

private fun sourceLabel(s: Measurement.Source) = when (s) {
    Measurement.Source.LIBRA -> "Libra"
    Measurement.Source.MANUAL -> "вручную"
    Measurement.Source.ZEPP -> "Zepp"
    Measurement.Source.SCALE -> "весы"
}
