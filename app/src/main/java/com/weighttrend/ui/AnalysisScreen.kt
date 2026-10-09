package com.weighttrend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.weighttrend.core.WeeklyAnalysis
import com.weighttrend.hc.ActivityReader
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Week-by-week: steps and sleep from Health Connect next to the energy balance; consultation report. */
@Composable
fun AnalysisScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.analysis.collectAsStateCompat()
    val points by vm.points.collectAsStateCompat()
    val askAccess = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.loadAnalysis(force = true)
    }
    LaunchedEffect(Unit) { vm.loadAnalysis() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ----- consultation report -----
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Отчёт для консультации", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Цифры за 8 недель одним текстом: вес и баланс, цель, состав тела, шаги, сон, тренировки и еда. " +
                        "Отправьте его в чат с Claude или консультантом.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { share(context, vm.buildReport()) }) { Text("Поделиться") }
                    OutlinedButton(onClick = { copy(context, vm.buildReport()) }) { Text("Копировать") }
                }
            }
        }

        Text(
            "Шаги и сон по неделям рядом с балансом энергии. Это сравнение, а не доказательство " +
                "причины: в «хорошие» недели могли меняться и еда, и режим.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
            state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Читаю Health Connect за полгода…", Modifier.padding(top = 12.dp))
            }
            !state.hasAccess || state.result == null -> {
                Text("Нужен доступ к шагам и сну в Health Connect (и, по желанию, к активным калориям, тренировкам и питанию), " +
                    "включая историю за прошлые месяцы.", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { askAccess.launch(ActivityReader.PERMISSIONS) }) { Text("Дать доступ") }
            }
            else -> Results(state.result!!, points.map { it.m.timestampMs to it.m.weightKg }) {
                askAccess.launch(ActivityReader.PERMISSIONS)
            }
        }

        if (state.result != null) {
            OutlinedButton(onClick = { vm.loadAnalysis(force = true) }) { Text("Обновить") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Отчёт для консультации"))
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Отчёт для консультации", text))
    Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
}

@Composable
private fun Results(r: ActivityReader.Result, weights: List<Pair<Long, Double>>, onAskMore: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val weeks = remember(r, weights) {
        WeeklyAnalysis.weeks(weights, r.steps, r.sleep, zone, r.extra)
            .filter { it.avgSteps != null || it.avgSleepHours != null }
    }

    val first = weeks.firstOrNull()?.start
    Text(
        if (first == null) "В Health Connect нет шагов и сна за последние полгода."
        else "Данные с ${Format.shortDate(first)} ${first.year}: недель с шагами ${r.steps.size}, со сном ${r.sleep.size}.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!r.historyGranted || r.weeksWithoutAccess > 0) {
        Text(
            "Health Connect отдал только последние ~30 дней: без разрешения на историю более старые данные недоступны.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
    if (r.missingOptional.isNotEmpty()) {
        Text("Нет доступа: ${r.missingOptional.joinToString(", ")} — для отчёта их можно разрешить.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!r.historyGranted || r.missingOptional.isNotEmpty()) {
        OutlinedButton(onClick = onAskMore) { Text("Разрешить") }
    }

    SplitCard(
        title = "Шаги",
        split = WeeklyAnalysis.split(weeks) { it.avgSteps },
        highLabel = { "≥ ${Format.int(it)} шагов в день" },
        rangeText = { s -> "от ${Format.int(s.minValue)} до ${Format.int(s.maxValue)} в день" },
        weeksWithData = weeks.count { it.avgSteps != null && it.balanceKcal != null },
    )
    SplitCard(
        title = "Сон",
        split = WeeklyAnalysis.split(weeks) { it.avgSleepHours },
        highLabel = { "≥ ${Format.num(it)} ч за ночь" },
        rangeText = { s -> "от ${Format.num(s.minValue)} до ${Format.num(s.maxValue)} ч" },
        weeksWithData = weeks.count { it.avgSleepHours != null && it.balanceKcal != null },
    )

    if (weeks.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Cell("Неделя", 1.3f, bold = true); Cell("Шаги", 1f, bold = true)
                    Cell("Сон", 0.8f, bold = true); Cell("Баланс", 1.1f, bold = true)
                }
                weeks.asReversed().forEach { w ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Cell(Format.shortDate(w.start), 1.3f)
                        Cell(w.avgSteps?.let { Format.int(it) } ?: "—", 1f)
                        Cell(w.avgSleepHours?.let { Format.num(it) + " ч" } ?: "—", 0.8f)
                        Cell(w.balanceKcal?.let { kcal(it) } ?: "—", 1.1f)
                    }
                }
                Text(
                    "Баланс недели — по изменению среднего веса соседних недель. Колебания воды всё равно дают " +
                        "разброс в сотни ккал, поэтому смысл имеет только сравнение многих недель.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SplitCard(
    title: String,
    split: WeeklyAnalysis.Split?,
    highLabel: (Double) -> String,
    rangeText: (WeeklyAnalysis.Split) -> String,
    weeksWithData: Int,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (split == null) {
                Text(
                    "Мало недель, где есть и ${title.lowercase()}, и взвешивания: $weeksWithData из ${WeeklyAnalysis.MIN_WEEKS} нужных.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Text("${highLabel(split.threshold)} (${split.highWeeks} нед.): ${kcal(split.highBalance)} в день",
                style = MaterialTheme.typography.bodyMedium)
            Text("меньше (${split.lowWeeks} нед.): ${kcal(split.lowBalance)} в день",
                style = MaterialTheme.typography.bodyMedium)
            val d = split.difference
            val noise = (2 * split.standardError).roundToInt()
            Text(
                when {
                    !split.significant ->
                        "По этим данным вывод сделать нельзя: разница ${abs(d).roundToInt()} ккал меньше случайных " +
                            "колебаний между неделями (±$noise ккал)."
                    d < 0 -> "В недели с бóльшим значением баланс в среднем на ${(-d).roundToInt()} ккал/день лучше " +
                        "(колебания ±$noise ккал)."
                    else -> "В недели с бóльшим значением баланс в среднем на ${d.roundToInt()} ккал/день хуже " +
                        "(колебания ±$noise ккал) — вероятно, вмешивается что-то ещё: еда, поездки."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (split.narrowRange) {
                Text(
                    "Значение почти не менялось (${rangeText(split)}), поэтому его влияние трудно увидеть.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text,
        Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

private fun kcal(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + "${Format.int(abs(v))} ккал"
