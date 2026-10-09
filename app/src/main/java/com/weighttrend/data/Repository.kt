package com.weighttrend.data

import android.content.Context
import com.weighttrend.core.BodyComposition
import com.weighttrend.core.LibraCsv
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

    fun importLibra(csv: String): Pair<Int, Int> = synchronized(lock) {
        val parsed = LibraCsv.parse(csv, ZoneId.systemDefault())
        val added = db.insertAll(parsed.measurements)
        reload()
        added to parsed.measurements.size
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
