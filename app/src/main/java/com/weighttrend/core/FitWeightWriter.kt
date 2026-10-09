package com.weighttrend.core

import java.io.ByteArrayOutputStream

/**
 * Minimal writer for a Garmin FIT "weight" file (file_id + weight_scale messages),
 * the format Garmin Connect accepts on its import page and that Garmin's own
 * Index scales produce.
 */
object FitWeightWriter {

    /** Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z). */
    private const val FIT_EPOCH_OFFSET = 631_065_600L

    private const val MSG_FILE_ID = 0
    private const val MSG_WEIGHT_SCALE = 30

    // FIT base types
    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    fun write(measurements: List<Measurement>): ByteArray {
        require(measurements.isNotEmpty()) { "Nothing to export" }
        val sorted = measurements.sortedBy { it.timestampMs }
        val data = ByteArrayOutputStream()

        // --- file_id definition (local message 0) + data ---
        data.definition(local = 0, global = MSG_FILE_ID, fields = listOf(
            Triple(0, 1, ENUM),      // type
            Triple(1, 2, UINT16),    // manufacturer
            Triple(2, 2, UINT16),    // product
            Triple(3, 4, UINT32Z),   // serial_number
            Triple(4, 4, UINT32),    // time_created
        ))
        data.write(0x00)
        data.write(9)                       // file type: weight
        data.u16(255)                       // manufacturer: development
        data.u16(0)
        data.u32(0x5754_0001L)              // arbitrary non-zero serial
        data.u32(fitTime(sorted.first().timestampMs))

        // --- weight_scale definition (local message 1) ---
        data.definition(local = 1, global = MSG_WEIGHT_SCALE, fields = listOf(
            Triple(253, 4, UINT32),  // timestamp
            Triple(0, 2, UINT16),    // weight, kg * 100
            Triple(1, 2, UINT16),    // percent_fat * 100
            Triple(2, 2, UINT16),    // percent_hydration * 100
            Triple(4, 2, UINT16),    // bone_mass, kg * 100
            Triple(5, 2, UINT16),    // muscle_mass, kg * 100
            Triple(11, 1, UINT8),    // visceral_fat_rating
        ))
        for (m in sorted) {
            data.write(0x01)
            data.u32(fitTime(m.timestampMs))
            data.u16(scaled(m.weightKg))
            data.u16(scaled(m.fatPercent))
            data.u16(scaled(m.waterPercent))
            data.u16(scaled(m.boneKg))
            data.u16(scaled(m.muscleKg))
            data.write(m.visceralFat?.let { Math.round(it).toInt().coerceIn(1, 59) } ?: 0xFF)
        }

        val body = data.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write(14)                 // header size
            write(0x20)               // protocol 2.0
            u16(2132)                 // profile version 21.32
            u32(body.size.toLong())
            write(".FIT".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        val headerCrc = crc16(header)

        val out = ByteArrayOutputStream()
        out.write(header)
        out.u16(headerCrc)
        out.write(body)
        out.u16(crc16(out.toByteArray()))
        return out.toByteArray()
    }

    private fun fitTime(epochMs: Long): Long = epochMs / 1000 - FIT_EPOCH_OFFSET

    private fun scaled(v: Double?): Int =
        if (v == null || v <= 0.0) 0xFFFF else Math.round(v * 100).toInt().coerceIn(0, 0xFFFE)

    private fun ByteArrayOutputStream.definition(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>) {
        write(0x40 or local)
        write(0)                // reserved
        write(0)                // architecture: little endian
        u16(global)
        write(fields.size)
        for ((num, size, type) in fields) { write(num); write(size); write(type) }
    }

    private fun ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            val byte = b.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }
}
