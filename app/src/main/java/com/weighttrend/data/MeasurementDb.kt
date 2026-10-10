package com.weighttrend.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.weighttrend.core.BodyMeasure
import com.weighttrend.core.Measurement
import java.time.LocalDate

class MeasurementDb(context: Context) : SQLiteOpenHelper(context, "weights.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                weight REAL NOT NULL,
                impedance INTEGER,
                fat REAL,
                water REAL,
                muscle REAL,
                bone REAL,
                visceral REAL,
                source TEXT NOT NULL,
                scale_key TEXT,
                hc_synced INTEGER NOT NULL DEFAULT 0,
                garmin_exported INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ts ON measurements(ts)")
        db.execSQL("CREATE UNIQUE INDEX idx_key ON measurements(source, scale_key)")
        createBodyMeasures(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBodyMeasures(db)
    }

    /** v2 (0.6): tape-measure circumferences, one row per day. */
    private fun createBodyMeasures(db: SQLiteDatabase) {
        val cols = BodyMeasure.Site.entries.joinToString(",\n") { "${it.column} REAL" }
        db.execSQL("CREATE TABLE body_measures (id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL UNIQUE,\n$cols)")
    }

    fun bodyMeasures(): List<BodyMeasure> =
        readableDatabase.query("body_measures", null, null, null, null, null, "day ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    val values = BodyMeasure.Site.entries.mapNotNull { s ->
                        val i = c.getColumnIndexOrThrow(s.column)
                        if (c.isNull(i)) null else s to c.getDouble(i)
                    }.toMap()
                    add(BodyMeasure(c.getLong(c.getColumnIndexOrThrow("id")),
                        LocalDate.ofEpochDay(c.getLong(c.getColumnIndexOrThrow("day"))), values))
                }
            }
        }

    /** Inserts or replaces the entry for that day (one entry per day). */
    fun saveBodyMeasure(b: BodyMeasure) {
        val cv = ContentValues().apply {
            put("day", b.date.toEpochDay())
            for (s in BodyMeasure.Site.entries) b[s]?.let { put(s.column, it) } ?: putNull(s.column)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (b.id != 0L) db.delete("body_measures", "id = ?", arrayOf(b.id.toString()))
            db.insertWithOnConflict("body_measures", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteBodyMeasure(id: Long) {
        writableDatabase.delete("body_measures", "id = ?", arrayOf(id.toString()))
    }

    fun all(): List<Measurement> =
        readableDatabase.query("measurements", null, null, null, null, null, "ts ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toMeasurement()) }
        }

    fun since(fromMs: Long): List<Measurement> =
        readableDatabase.query("measurements", null, "ts >= ?", arrayOf(fromMs.toString()), null, null, "ts ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toMeasurement()) }
        }

    /** Returns the new row id, or -1 if a row with the same (source, scale_key) exists. */
    fun insert(m: Measurement): Long =
        writableDatabase.insertWithOnConflict("measurements", null, m.toValues(), SQLiteDatabase.CONFLICT_IGNORE)

    fun insertAll(list: List<Measurement>): Int {
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in list) if (db.insertWithOnConflict("measurements", null, m.toValues(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun update(m: Measurement) {
        writableDatabase.update("measurements", m.toValues(), "id = ?", arrayOf(m.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("measurements", "id = ?", arrayOf(id.toString()))
    }

    fun markHealthConnectSynced(ids: Collection<Long>) = setFlag("hc_synced", ids)
    fun markGarminExported(ids: Collection<Long>) = setFlag("garmin_exported", ids)

    private fun setFlag(column: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put(column, 1) }
            for (id in ids) db.update("measurements", cv, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Measurement.toValues() = ContentValues().apply {
        put("ts", timestampMs)
        put("weight", weightKg)
        put("impedance", impedanceOhm)
        put("fat", fatPercent)
        put("water", waterPercent)
        put("muscle", muscleKg)
        put("bone", boneKg)
        put("visceral", visceralFat)
        put("source", source.name)
        put("scale_key", scaleKey)
        put("hc_synced", if (healthConnectSynced) 1 else 0)
        put("garmin_exported", if (garminExported) 1 else 0)
    }

    private fun Cursor.toMeasurement(): Measurement {
        fun dbl(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
        fun int(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }
        return Measurement(
            id = getLong(getColumnIndexOrThrow("id")),
            timestampMs = getLong(getColumnIndexOrThrow("ts")),
            weightKg = getDouble(getColumnIndexOrThrow("weight")),
            impedanceOhm = int("impedance"),
            fatPercent = dbl("fat"),
            waterPercent = dbl("water"),
            muscleKg = dbl("muscle"),
            boneKg = dbl("bone"),
            visceralFat = dbl("visceral"),
            source = runCatching { Measurement.Source.valueOf(getString(getColumnIndexOrThrow("source"))) }
                .getOrDefault(Measurement.Source.MANUAL),
            scaleKey = getColumnIndexOrThrow("scale_key").let { if (isNull(it)) null else getString(it) },
            healthConnectSynced = getInt(getColumnIndexOrThrow("hc_synced")) == 1,
            garminExported = getInt(getColumnIndexOrThrow("garmin_exported")) == 1,
        )
    }
}
