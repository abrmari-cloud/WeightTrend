package com.weighttrend.core

/**
 * Decoder for the 13-byte service-data payload (UUID 0x181B) that the
 * Xiaomi Mi Body Composition Scale 2 (MIBFS / XMTZC05HM) broadcasts.
 *
 * Layout (cross-checked against openScale's MiScaleHandler and Home Assistant's
 * xiaomi-ble parser):
 *   [0]      control byte 0: bit0 = pounds
 *   [1]      control byte 1: bit1 = impedance present, bit5 = stabilized,
 *                            bit6 = catty, bit7 = load removed
 *   [2..3]   year (little endian)
 *   [4..8]   month, day, hour, minute, second (scale clock)
 *   [9..10]  impedance in ohm (little endian)
 *   [11..12] weight raw (little endian): kg * 200, or lb/catty * 100
 */
data class MiScaleFrame(
    val weightKg: Double,
    val impedanceOhm: Int?,
    val stabilized: Boolean,
    val loadRemoved: Boolean,
    val scaleYear: Int,
    val scaleMonth: Int,
    val scaleDay: Int,
    val scaleHour: Int,
    val scaleMinute: Int,
    val scaleSecond: Int,
) {
    /** True for a final, usable reading (what Zepp Life would save). */
    val isFinal: Boolean get() = stabilized && !loadRemoved && weightKg > 0.0

    /** Identity of the measurement as stamped by the scale's own clock. */
    val scaleKey: String
        get() = "%04d%02d%02d%02d%02d%02d".format(
            scaleYear, scaleMonth, scaleDay, scaleHour, scaleMinute, scaleSecond
        )

    companion object {
        const val SERVICE_UUID = "0000181b-0000-1000-8000-00805f9b34fb"

        fun parse(d: ByteArray): MiScaleFrame? {
            if (d.size != 13) return null
            fun u8(i: Int) = d[i].toInt() and 0xFF
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

            val c0 = u8(0)
            val c1 = u8(1)
            val isLbs = (c0 and 0x01) != 0
            val isCatty = (c1 and 0x40) != 0
            val stabilized = (c1 and 0x20) != 0
            val removed = (c1 and 0x80) != 0
            val hasImpedance = (c1 and 0x02) != 0

            val raw = u16(11)
            val kg = when {
                isLbs -> raw / 100.0 * 0.45359237
                isCatty -> raw / 100.0 * 0.5
                else -> raw / 200.0
            }
            val imp = u16(9)
            return MiScaleFrame(
                weightKg = kg,
                impedanceOhm = if (hasImpedance && imp in 1..2999) imp else null,
                stabilized = stabilized,
                loadRemoved = removed,
                scaleYear = u16(2),
                scaleMonth = u8(4),
                scaleDay = u8(5),
                scaleHour = u8(6),
                scaleMinute = u8(7),
                scaleSecond = u8(8),
            )
        }
    }
}
