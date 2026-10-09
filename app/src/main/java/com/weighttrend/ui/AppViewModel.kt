package com.weighttrend.ui

import android.app.Application
import android.bluetooth.le.ScanResult
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrend.ble.ScaleScanner
import com.weighttrend.core.FitWeightWriter
import com.weighttrend.core.Goal
import com.weighttrend.core.LibraCsv
import com.weighttrend.core.Measurement
import com.weighttrend.core.MiScaleFrame
import com.weighttrend.core.Trend
import com.weighttrend.core.UserProfile
import com.weighttrend.data.Repository
import com.weighttrend.garmin.GarminStore
import com.weighttrend.garmin.GarminSync
import com.weighttrend.hc.ActivityReader
import com.weighttrend.hc.HealthConnectSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A measurement together with its trend value at that moment. */
data class TrendPoint(val m: Measurement, val trend: Double)

data class FoundScale(val address: String, val name: String, val rssi: Int)

data class LiveReading(val weightKg: Double, val stable: Boolean, val hasImpedance: Boolean, val atMs: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repository.get(app)

    val points: StateFlow<List<TrendPoint>> = repo.measurements
        .map { list ->
            val t = Trend.compute(list.map { it.timestampMs to it.weightKg })
            list.mapIndexed { i, m -> TrendPoint(m, t[i]) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _profile = MutableStateFlow(repo.settings.profile)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _scaleAddress = MutableStateFlow(repo.settings.scaleAddress)
    val scaleAddress: StateFlow<String?> = _scaleAddress.asStateFlow()

    private val _hcEnabled = MutableStateFlow(repo.settings.healthConnectEnabled)
    val hcEnabled: StateFlow<Boolean> = _hcEnabled.asStateFlow()

    private val _hcPermitted = MutableStateFlow(false)
    val hcPermitted: StateFlow<Boolean> = _hcPermitted.asStateFlow()

    private val _found = MutableStateFlow<List<FoundScale>>(emptyList())
    val found: StateFlow<List<FoundScale>> = _found.asStateFlow()

    private val _live = MutableStateFlow<LiveReading?>(null)
    val live: StateFlow<LiveReading?> = _live.asStateFlow()

    /** One-shot messages for a snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    private val liveScan = ScaleScanner.LiveScan(app) { onScanResult(it) }

    init {
        viewModelScope.launch(Dispatchers.IO) { repo.migrateIfNeeded() }
    }

    val hcAvailable: Boolean get() = HealthConnectSync.isAvailable(getApplication<Application>())

    // ---------- Garmin ----------

    data class GarminState(
        val connected: Boolean,
        val needsLogin: Boolean,
        val status: String?,
        val lastSuccessMs: Long,
        val pending: Int,
    )

    private val garminStore = GarminStore(app)
    private val _garmin = MutableStateFlow(readGarmin())
    val garmin: StateFlow<GarminState> = _garmin.asStateFlow()

    private fun readGarmin() = GarminState(
        connected = garminStore.isConnected,
        needsLogin = garminStore.needsLogin,
        status = garminStore.lastStatus,
        lastSuccessMs = garminStore.lastSuccessMs,
        pending = GarminSync.pending(repo).size,
    )

    fun refreshGarmin() { _garmin.value = readGarmin() }

    init {
        // Declared after the Garmin state: init blocks run in source order.
        viewModelScope.launch { repo.measurements.collect { refreshGarmin() } }
    }

    fun garminSyncNow() = viewModelScope.launch {
        _message.value = when (val o = GarminSync.syncNow(getApplication<Application>(), repo)) {
            is GarminSync.Outcome.Sent -> "Отправлено в Garmin: ${o.count}"
            GarminSync.Outcome.NothingToSend -> "Garmin: новых взвешиваний нет"
            GarminSync.Outcome.NotConnected -> "Garmin не подключён"
            GarminSync.Outcome.NeedsLogin -> "Garmin: нужно войти заново"
            is GarminSync.Outcome.NetworkError -> "Garmin: нет связи (${o.message})"
            is GarminSync.Outcome.Failed -> "Garmin: ${o.message}"
        }
        refreshGarmin()
    }

    fun garminLogout() {
        garminStore.clear()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        refreshGarmin()
    }

    // ---------- Bluetooth ----------

    fun onForeground() {
        liveScan.start()
        ScaleScanner.ensureBackgroundScan(getApplication<Application>())
        refreshHealthConnect(syncAfter = true)
        refreshGarmin()
        if (_garmin.value.connected && _garmin.value.pending > 0) GarminSync.schedule(getApplication<Application>(), 10)
    }

    fun onBackground() = liveScan.stop()

    /** Called after permissions were granted. */
    fun restartScans() {
        liveScan.stop()
        liveScan.start()
        ScaleScanner.ensureBackgroundScan(getApplication<Application>())
    }

    private fun onScanResult(r: ScanResult) {
        val bound = _scaleAddress.value
        if (bound == null) {
            if (ScaleScanner.looksLikeScale(r)) {
                val f = FoundScale(r.device.address, r.scanRecord?.deviceName ?: "Весы", r.rssi)
                _found.value = (_found.value.filter { it.address != f.address } + f).sortedByDescending { it.rssi }
            }
            return
        }
        if (r.device.address != bound) return
        val frame: MiScaleFrame = ScaleScanner.frameOf(r) ?: return
        val now = System.currentTimeMillis()
        if (frame.weightKg > 0 && !frame.loadRemoved) {
            _live.value = LiveReading(frame.weightKg, frame.stabilized, frame.impedanceOhm != null, now)
        }
        if (frame.isFinal) {
            viewModelScope.launch(Dispatchers.IO) {
                repo.onScaleFrame(frame, now)
                if (frame.impedanceOhm != null) HealthConnectSync.trySync(getApplication<Application>(), repo)
            }
        }
    }

    fun bindScale(address: String) {
        repo.settings.scaleAddress = address
        _scaleAddress.value = address
        _found.value = emptyList()
        ScaleScanner.ensureBackgroundScan(getApplication<Application>())
        _message.value = "Весы привязаны. Встаньте на них босиком, чтобы проверить."
    }

    fun unbindScale() {
        ScaleScanner.stopBackgroundScan(getApplication<Application>())
        repo.settings.scaleAddress = null
        _scaleAddress.value = null
        _live.value = null
    }

    // ---------- Profile ----------

    fun saveProfile(p: UserProfile) {
        repo.settings.profile = p
        _profile.value = p
        viewModelScope.launch(Dispatchers.IO) {
            val n = repo.recomputeComposition()
            repo.backfillImpedance()
            _message.value = if (n > 0) "Профиль сохранён, пересчитано взвешиваний: $n" else "Профиль сохранён"
        }
    }

    // ---------- Goal ----------

    private val _goal = MutableStateFlow(repo.settings.goal)
    val goal: StateFlow<Goal?> = _goal.asStateFlow()

    fun saveGoal(g: Goal?) {
        repo.settings.goal = g
        _goal.value = g
        _message.value = if (g == null) "Цель убрана" else "Цель сохранена"
    }

    // ---------- Steps & sleep analysis ----------

    data class AnalysisState(
        val loading: Boolean = false,
        val hasAccess: Boolean = false,
        val result: ActivityReader.Result? = null,
        val error: String? = null,
    )

    private val _analysis = MutableStateFlow(AnalysisState())
    val analysis: StateFlow<AnalysisState> = _analysis.asStateFlow()

    fun loadAnalysis(force: Boolean = false) {
        if (_analysis.value.loading || (!force && _analysis.value.result != null)) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (!HealthConnectSync.isAvailable(app)) {
                _analysis.value = AnalysisState(error = "Health Connect на этом телефоне недоступен")
                return@launch
            }
            val access = runCatching { ActivityReader.hasAccess(app) }.getOrDefault(false)
            if (!access) { _analysis.value = AnalysisState(hasAccess = false); return@launch }
            _analysis.value = AnalysisState(loading = true, hasAccess = true)
            _analysis.value = runCatching { ActivityReader.read(app) }.fold(
                { AnalysisState(hasAccess = true, result = it) },
                { AnalysisState(hasAccess = true, error = it.message ?: "Ошибка чтения Health Connect") },
            )
        }
    }

    // ---------- Health Connect ----------

    fun setHealthConnectEnabled(on: Boolean) {
        repo.settings.healthConnectEnabled = on
        _hcEnabled.value = on
        if (on) refreshHealthConnect(syncAfter = true)
    }

    fun refreshHealthConnect(syncAfter: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _hcPermitted.value = runCatching { HealthConnectSync.hasPermissions(getApplication<Application>()) }.getOrDefault(false)
            if (syncAfter && _hcPermitted.value) {
                val n = HealthConnectSync.trySync(getApplication<Application>(), repo)
                if (n > 0) _message.value = "В Health Connect записано взвешиваний: $n"
            }
        }
    }

    fun syncHealthConnectNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = HealthConnectSync.trySync(getApplication<Application>(), repo)
            _message.value = when {
                n < 0 -> "Health Connect: ошибка записи"
                n == 0 -> "Health Connect: нового нет"
                else -> "В Health Connect записано взвешиваний: $n"
            }
        }
    }

    // ---------- Data ----------

    fun importLibra(uri: Uri) = importHistory(uri, "Libra") { repo.importLibra(it) }
    fun importZepp(uri: Uri) = importHistory(uri, "Zepp Life") { repo.importZepp(it) }

    private fun importHistory(uri: Uri, name: String, block: (String) -> Repository.ImportResult) =
        viewModelScope.launch(Dispatchers.IO) {
            val text = readText(uri) ?: run { _message.value = "Не удалось прочитать файл"; return@launch }
            val r = runCatching { block(text) }.getOrElse { _message.value = "Импорт $name: ${it.message}"; return@launch }
            _message.value = buildString {
                append("Импорт $name: новых ${r.added}")
                if (r.enriched > 0) append(", дополнено ${r.enriched}")
                if (r.duplicates > 0) append(", уже было ${r.duplicates}")
                if (r.note.isNotEmpty()) append("; ").append(r.note)
            }
        }

    fun exportFit(uri: Uri, onlyNew: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val list = repo.measurements.value.filter { !onlyNew || !it.garminExported }
        if (list.isEmpty()) { _message.value = "Нет новых взвешиваний для экспорта"; return@launch }
        writeBytes(uri, FitWeightWriter.write(list)) ?: return@launch
        repo.markGarminExported(list.map { it.id })
        _message.value = "FIT-файл сохранён: ${list.size} взвешиваний"
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val list = repo.measurements.value
        writeBytes(uri, LibraCsv.write(list).toByteArray()) ?: return@launch
        _message.value = "Резервная копия сохранена: ${list.size} взвешиваний"
    }

    fun addManual(weightKg: Double) = viewModelScope.launch(Dispatchers.IO) {
        repo.addManual(weightKg, System.currentTimeMillis())
        HealthConnectSync.trySync(getApplication<Application>(), repo)
        GarminSync.schedule(getApplication<Application>(), 10)
    }

    fun delete(id: Long) = viewModelScope.launch(Dispatchers.IO) { repo.delete(id) }

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray): Unit? =
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        }.getOrNull().also { if (it == null) _message.value = "Не удалось сохранить файл" }

    override fun onCleared() {
        liveScan.stop()
    }
}
