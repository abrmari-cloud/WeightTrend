package com.weighttrend.data

import android.content.Context
import com.weighttrend.core.BodyComposition
import com.weighttrend.core.HistoryMerge
import com.weighttrend.core.LibraCsv
import com.weighttrend.core.ZeppCsv
import com.weighttrend.core.Measurement
import com.weighttrend.core.MeasurementAssembler
import com.weighttrend.core.MiScaleFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single source of truth for measurements. One instance per process, shared by
 * the UI and the background scan receiver; all writes are serialized.
 */
class Repository private constructor(context: Context) {

    private val db = MeasurementDb(context.applicationContext)
    val settings = Settings(context.applicationContext)

    private val _measurements = MutableStateFlow<List<Measurement>>(emptyList())
    val measurements: StateFlow<List<Measurement>> = _measurements.asStateFlow()

    private val lock = Any()

    fun reload() {
        _measurements.value = db.all()
    }

    /** Composition calculator for the current profile, or null if no profile yet. */
    fun compositionFor(timestampMs: Long): ((Double, Int) -> BodyComposition.Result)? {
        val p = settings.profile ?: return null
        val d = java.time.Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val calc = BodyComposition(p, p.ageAt(d.year, d.monthValue))
        return { w, imp -> calc.compute(w, imp) }
    }

    /** Handles one scale advertisement. Returns the saved/updated measurement, if any. */
    fun onScaleFrame(frame: MiScaleFrame, receivedAtMs: Long): Measurement? = synchronized(lock) {
        if (!frame.isFinal) return null
        val recent = db.since(receivedAtMs - 24 * 3600_000L)
        val result = when (val action = MeasurementAssembler.decide(frame, receivedAtMs, recent, compositionFor(receivedAtMs))) {
            is MeasurementAssembler.Action.Insert -> {
                val id = db.insert(action.measurement)
                if (id == -1L) null else action.measurement.copy(id = id)
            }
            is MeasurementAssembler.Action.AddImpedance -> {
                db.update(action.updated)
                action.updated
            }
            MeasurementAssembler.Action.Ignore -> null
        }
        if (result != null) reload()
        result
    }

    data class ImportResult(val added: Int, val enriched: Int, val duplicates: Int, val note: String = "")

    /** Adds history without duplicating weigh-ins the app already has (see [HistoryMerge]). */
    private fun mergeIn(incoming: List<Measurement>): ImportResult {
        var added = 0; var enriched = 0; var dup = 0
        for (op in HistoryMerge.plan(db.all(), incoming, ZoneId.systemDefault())) when (op) {
            is HistoryMerge.Op.Insert -> if (db.insert(op.m) != -1L) added++ else dup++
            is HistoryMerge.Op.Replace -> { db.update(op.updated); enriched++ }
            is HistoryMerge.Op.Skip -> dup++
        }
        reload()
        return ImportResult(added, enriched, dup)
    }

    fun importLibra(csv: String): ImportResult = synchronized(lock) {
        mergeIn(LibraCsv.parse(csv, ZoneId.systemDefault()).measurements)
    }

    fun importZepp(csv: String): ImportResult = synchronized(lock) {
        val parsed = ZeppCsv.parse(csv, settings.profile?.heightCm)
        val note = if (parsed.otherPeopleRows > 0)
            "пропущено чужих взвешиваний: ${parsed.otherPeopleRows}" else ""
        mergeIn(parsed.measurements).copy(note = note)
    }

    fun addManual(weightKg: Double, timestampMs: Long) = synchronized(lock) {
        db.insert(
            Measurement(
                timestampMs = timestampMs,
                weightKg = weightKg,
                source = Measurement.Source.MANUAL,
                scaleKey = "manual-$timestampMs",
            )
        )
        reload()
    }

    fun delete(id: Long) = synchronized(lock) {
        db.delete(id)
        reload()
    }

    /**
     * Version 0.2 switched muscle mass to Zepp's definition; recompute readings
     * saved by 0.1 once.
     */
    fun migrateIfNeeded() = synchronized(lock) {
        if (settings.compositionVersion < 2) {
            recomputeComposition()
            settings.compositionVersion = 2
        }
    }

    /** Recompute body composition for all scale readings after the profile changed. */
    fun recomputeComposition(): Int = synchronized(lock) {
        var n = 0
        for (m in db.all()) {
            if (m.impedanceOhm == null) continue
            val updated = MeasurementAssembler.withComposition(m, compositionFor(m.timestampMs))
            if (updated != m) { db.update(updated); n++ }
        }
        reload()
        n
    }

    fun markHealthConnectSynced(ids: Collection<Long>) = synchronized(lock) { db.markHealthConnectSynced(ids); reload() }
    fun markGarminExported(ids: Collection<Long>) = synchronized(lock) { db.markGarminExported(ids); reload() }

    fun localDate(m: Measurement): LocalDate =
        java.time.Instant.ofEpochMilli(m.timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context).also { it.reload(); instance = it }
            }
    }
}
