package com.weighttrend.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.weighttrend.core.Coach
import com.weighttrend.core.Goal
import com.weighttrend.core.UserProfile
import com.weighttrend.garmin.GarminLoginActivity
import com.weighttrend.hc.HealthConnectSync
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val profile by vm.profile.collectAsStateCompat()
    val goal by vm.goal.collectAsStateCompat()
    val points by vm.points.collectAsStateCompat()
    val scale by vm.scaleAddress.collectAsStateCompat()
    val found by vm.found.collectAsStateCompat()
    val hcEnabled by vm.hcEnabled.collectAsStateCompat()
    val hcPermitted by vm.hcPermitted.collectAsStateCompat()
    val garmin by vm.garmin.collectAsStateCompat()

    val btPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.restartScans()
    }
    val hcPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshHealthConnect(syncAfter = true)
    }
    val importLibra = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importLibra(it) }
    }
    val importZepp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importZepp(it) }
    }
    val exportNewFit = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportFit(it, onlyNew = true) }
    }
    val exportAllFit = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportFit(it, onlyNew = false) }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(it) }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- Profile ----------
        Section("Профиль", "Нужен для расчёта состава тела по импедансу.") {
            ProfileForm(profile) { vm.saveProfile(it) }
        }

        Section("Цель", "Целевой вес по тренду и, по желанию, дата. Без даты считаю по спокойному темпу 0,5 % веса в неделю.") {
            GoalForm(goal, points.lastOrNull()?.trend) { vm.saveGoal(it) }
        }

        // ---------- Scale ----------
        Section("Весы", "Mi Body Composition Scale 2. Приложение слушает весы в фоне, без сопряжения; Zepp Life можно не удалять.") {
            if (scale == null) {
                FilledTonalButton(onClick = {
                    btPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Найти весы") }
                Text(
                    if (found.isEmpty()) "Встаньте на весы, чтобы они проснулись, и подождите несколько секунд."
                    else "Нажмите на свои весы:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                found.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.bindScale(s.address) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            Text(s.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${s.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text("Привязаны: $scale", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.unbindScale() }) { Text("Отвязать") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text("Батарея") }
                }
                Text(
                    "Если взвешивания не сохраняются при закрытом приложении, снимите для «Вес» ограничения батареи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- Health Connect ----------
        Section("Health Connect", "Новые взвешивания с весов (вес, жир, кости) записываются в Health Connect. Импортированная история не пишется, чтобы не было дублей.") {
            if (!vm.hcAvailable) {
                Text("Health Connect на этом телефоне недоступен.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Записывать в Health Connect", Modifier.weight(1f))
                    Switch(checked = hcEnabled, onCheckedChange = { on ->
                        vm.setHealthConnectEnabled(on)
                        if (on && !hcPermitted) hcPermissions.launch(HealthConnectSync.PERMISSIONS)
                    })
                }
                if (hcEnabled && !hcPermitted) {
                    FilledTonalButton(onClick = { hcPermissions.launch(HealthConnectSync.PERMISSIONS) }) { Text("Дать доступ") }
                }
                if (hcEnabled && hcPermitted) {
                    OutlinedButton(onClick = { vm.syncHealthConnectNow() }) { Text("Синхронизировать сейчас") }
                }
            }
        }

        // ---------- Garmin ----------
        Section(
            "Garmin Connect",
            "Новые взвешивания уходят в Garmin сами, примерно через полторы минуты после взвешивания. " +
                "Вход — на странице Garmin, пароль приложению не передаётся. Это неофициальный способ: " +
                "если Garmin его поменяет, отправка остановится, а взвешивания останутся в приложении.",
        ) {
            if (!garmin.connected || garmin.needsLogin) {
                if (garmin.needsLogin) {
                    Text("Garmin не принял сохранённый вход. Войдите заново — накопленные взвешивания отправятся.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                FilledTonalButton(onClick = {
                    context.startActivity(Intent(context, GarminLoginActivity::class.java))
                }) { Text("Войти в Garmin") }
                if (!garmin.connected) {
                    Text(
                        "После первого входа автоматически отправляются только новые взвешивания. " +
                            "Историю загрузите один раз файлом (кнопка «Всё → FIT» ниже).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    buildString {
                        append("Подключено")
                        if (garmin.lastSuccessMs > 0) append(" · последняя отправка: ").append(Format.dateTime(garmin.lastSuccessMs))
                        append(" · ждут отправки: ").append(garmin.pending)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                garmin.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.garminSyncNow() }) { Text("Отправить сейчас") }
                    OutlinedButton(onClick = { vm.garminLogout() }) { Text("Выйти") }
                }
            }
            Text("Файлом:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportNewFit.launch("weight_new_${LocalDate.now()}.fit") }) { Text("Новые → FIT") }
                OutlinedButton(onClick = { exportAllFit.launch("weight_all_${LocalDate.now()}.fit") }) { Text("Всё → FIT") }
            }
        }

        // ---------- Data ----------
        Section(
            "Данные",
            "Импорт истории из Libra (CSV-экспорт) и Zepp Life (файл BODY из экспорта). " +
                "Повторяющиеся взвешивания не дублируются. Резервная копия сохраняется в формате Libra.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { importLibra.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("Импорт Libra") }
                FilledTonalButton(onClick = { importZepp.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("Импорт Zepp") }
            }
            OutlinedButton(onClick = { exportCsv.launch("weight_backup_${LocalDate.now()}.csv") }) { Text("Резервная копия") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ProfileForm(current: UserProfile?, onSave: (UserProfile) -> Unit) {
    var male by remember(current) { mutableStateOf(current?.isMale ?: false) }
    var height by remember(current) { mutableStateOf(current?.heightCm?.let { Format.num(it, 0) } ?: "") }
    var year by remember(current) { mutableStateOf(current?.birthYear?.toString() ?: "") }
    var month by remember(current) { mutableStateOf(current?.birthMonth?.toString() ?: "") }

    val h = height.replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..230.0 }
    val y = year.toIntOrNull()?.takeIf { it in 1920..LocalDate.now().year - 6 }
    val m = month.toIntOrNull()?.takeIf { it in 1..12 }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !male, onClick = { male = false }, label = { Text("Женщина") })
        FilterChip(selected = male, onClick = { male = true }, label = { Text("Мужчина") })
    }
    OutlinedTextField(
        value = height, onValueChange = { height = it }, label = { Text("Рост, см") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = year, onValueChange = { year = it }, label = { Text("Год рождения") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = month, onValueChange = { month = it }, label = { Text("Месяц (1–12)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
        )
    }
    Button(enabled = h != null && y != null && m != null, onClick = {
        onSave(UserProfile(isMale = male, birthYear = y!!, birthMonth = m!!, heightCm = h!!))
    }) { Text("Сохранить") }
}

@Composable
private fun GoalForm(current: Goal?, trendKg: Double?, onSave: (Goal?) -> Unit) {
    var weight by remember(current) { mutableStateOf(current?.targetKg?.let { Format.num(it) } ?: "") }
    var date by remember(current) { mutableStateOf(current?.targetDate?.let { DATE_INPUT.format(it) } ?: "") }

    val w = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..250.0 }
    val d = date.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, DATE_INPUT) }.getOrNull() }
    val dateOk = date.isBlank() || (d != null && d.isAfter(LocalDate.now()))

    OutlinedTextField(
        value = weight, onValueChange = { weight = it }, label = { Text("Целевой вес, кг") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = date, onValueChange = { date = it }, label = { Text("Дата (дд.мм.гггг), необязательно") }, singleLine = true,
        isError = !dateOk,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    if (w != null && trendKg != null && dateOk) {
        val g = Goal(w, d)
        val today = LocalDate.now()
        val pace = Coach.requiredPacePct(trendKg, g, today)
        val kcal = Coach.required(trendKg, g, today)
        val text = buildString {
            append("Сейчас тренд ${Format.kg(trendKg)}. ")
            if (kotlin.math.abs(w - trendKg) <= 0.3) append("Вы уже у цели.")
            else {
                append("Нужно ${if (kcal < 0) "−" else "+"}${kotlin.math.abs(kcal).toInt()} ккал в день, темп ${Format.num(pace)} % веса в неделю.")
                if (pace > Coach.MAX_PACE_PCT) {
                    append(" Это быстрее безопасного (${Format.num(Coach.MAX_PACE_PCT)} %); реалистичная дата — ")
                    append(DATE_INPUT.format(Coach.earliestSafeDate(trendKg, g, today))).append(".")
                }
            }
        }
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (pace > Coach.MAX_PACE_PCT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = w != null && dateOk, onClick = { onSave(Goal(w!!, d)) }) { Text("Сохранить цель") }
        if (current != null) OutlinedButton(onClick = { onSave(null) }) { Text("Убрать") }
    }
}

private val DATE_INPUT: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
