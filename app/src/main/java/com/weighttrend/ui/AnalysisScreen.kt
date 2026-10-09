package com.weighttrend.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.weighttrend.core.WeeklyAnalysis
import com.weighttrend.hc.ActivityReader
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Week-by-week: steps and sleep from Health Connect next to the energy balance. */
@Composable
fun AnalysisScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
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
        Text(
            "Шаги и сон по неделям рядом с балансом энергии по тренду веса. Это сравнение, а не доказательство " +
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
                Text("Нужен доступ к шагам и сну в Health Connect, включая историю за прошлые месяцы.",
                    style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { askAccess.launch(ActivityReader.PERMISSIONS) }) { Text("Дать доступ") }
            }
            else -> Results(state.result!!, points.map { it.m.timestampMs to it.trend }) { askAccess.launch(ActivityReader.PERMISSIONS) }
        }

        if (state.result != null) {
            OutlinedButton(onClick = { vm.loadAnalysis(force = true) }) { Text("Обновить") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Results(r: ActivityReader.Result, trend: List<Pair<Long, Double>>, onAskHistory: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val weeks = remember(r, trend) { WeeklyAnalysis.weeks(trend, r.steps, r.sleep, zone).filter { it.avgSteps != null || it.avgSleepHours != null } }

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
        OutlinedButton(onClick = onAskHistory) { Text("Разрешить историю") }
    }

    SplitCard(
        title = "Шаги",
        split = WeeklyAnalysis.split(weeks) { it.avgSteps },
        highLabel = { "≥ ${String.format("%,d", it.roundToInt())} шагов в день" },
        lowLabel = { "меньше" },
        weeksWithData = weeks.count { it.avgSteps != null && it.balanceKcal != null },
    )
    SplitCard(
        title = "Сон",
        split = WeeklyAnalysis.split(weeks) { it.avgSleepHours },
        highLabel = { "≥ ${Format.num(it)} ч за ночь" },
        lowLabel = { "меньше" },
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
                        Cell(w.avgSteps?.let { String.format("%,d", it.roundToInt()) } ?: "—", 1f)
                        Cell(w.avgSleepHours?.let { Format.num(it) + " ч" } ?: "—", 0.8f)
                        Cell(w.balanceKcal?.let { kcal(it) } ?: "—", 1.1f)
                    }
                }
                Text(
                    "Баланс недели — по тренду веса между первым и последним взвешиванием недели; нужно минимум 2 взвешивания.",
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
    lowLabel: (Double) -> String,
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
            Text("${lowLabel(split.threshold)} (${split.lowWeeks} нед.): ${kcal(split.lowBalance)} в день",
                style = MaterialTheme.typography.bodyMedium)
            val d = split.difference
            Text(
                when {
                    abs(d) < 75 -> "Заметной разницы нет: по твоим данным этот фактор на баланс почти не влияет."
                    d < 0 -> "В недели с бóльшим значением баланс в среднем на ${(-d).roundToInt()} ккал/день лучше."
                    else -> "В недели с бóльшим значением баланс в среднем на ${d.roundToInt()} ккал/день хуже — " +
                        "вероятно, вмешивается что-то ещё (еда, поездки)."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text,
        Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

private fun kcal(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + "${abs(v).roundToInt()} ккал"
