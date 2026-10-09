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
import com.weighttrend.core.UserProfile
import com.weighttrend.hc.HealthConnectSync
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val profile by vm.profile.collectAsStateCompat()
    val scale by vm.scaleAddress.collectAsStateCompat()
    val found by vm.found.collectAsStateCompat()
    val hcEnabled by vm.hcEnabled.collectAsStateCompat()
    val hcPermitted by vm.hcPermitted.collectAsStateCompat()

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
        Section("Garmin", "Пока через файл: сохраните FIT и загрузите его на connect.garmin.com/app/import-data. Автоматическая отправка — следующий этап.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { exportNewFit.launch("weight_new_${LocalDate.now()}.fit") }) { Text("Новые → FIT") }
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
