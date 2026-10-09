package com.weighttrend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CoreTest {

    private fun frame(c1: Int, imp: Int, raw: Int) = byteArrayOf(
        0x02, c1.toByte(), (2026 and 0xFF).toByte(), (2026 shr 8).toByte(), 10, 8, 6, 38, 28,
        (imp and 0xFF).toByte(), (imp shr 8).toByte(), (raw and 0xFF).toByte(), (raw shr 8).toByte(),
    )

    @Test fun parsesStableFrameWithImpedance() {
        val f = MiScaleFrame.parse(frame(0x22, 480, 12770))!!
        assertEquals(63.85, f.weightKg, 1e-9)
        assertEquals(480, f.impedanceOhm)
        assertTrue(f.isFinal)
        assertEquals("20261008063828", f.scaleKey)
    }

    @Test fun ignoresUnstableAndRemoved() {
        assertFalse(MiScaleFrame.parse(frame(0x00, 0, 12000))!!.isFinal)
        assertFalse(MiScaleFrame.parse(frame(0xA2, 480, 12770))!!.isFinal)
        assertNull(MiScaleFrame.parse(ByteArray(10)))
    }

    /** Reference values from openScale's MiScaleLibTest regression fixtures. */
    @Test fun compositionMatchesOpenScale() {
        val male = BodyComposition(UserProfile(true, 1996, 1, 180.0), 30).compute(80.0, 500)
        assertEquals(23.3, male.fatPercent, 1e-9)
        assertEquals(52.6, male.waterPercent, 1e-9)
        assertEquals(3.1, male.boneKg, 1e-9)
        assertEquals(58.2, male.muscleKg, 1e-9) // = weight − fat − bone, as in Zepp Life
        val female = BodyComposition(UserProfile(false, 1998, 1, 165.0), 28).compute(60.0, 520)
        assertEquals(30.4, female.fatPercent, 1e-9)
        assertEquals(49.7, female.waterPercent, 1e-9)
        assertNull(female.visceralFat) // formula is implausible for this case → dropped
    }

    @Test fun trendFollowsSevenDayTimeConstant() {
        val day = 86_400_000L
        val t = Trend.compute(listOf(0L to 60.0, 7 * day to 61.0))
        // after exactly tau, the trend has moved 1 - 1/e ≈ 63.2 % of the gap
        assertEquals(60.632, t[1], 1e-3)
    }

    @Test fun libraDuplicatesCollapse() {
        val csv = """
            #Version: 6
            #Units: kg

            #date;weight;weight trend;body fat;body fat trend;muscle mass;muscle mass trend;log
            2018-01-18T23:00:00.000Z;61.5;61.5;;;;;
            2018-01-19T17:53:00.000Z;61.5;61.5;;;;;
            2018-01-19T23:00:00.000Z;60.2;61.5;;;;;
            2023-01-13T22:19:24.978691Z;64.0;64.0;;;;;
        """.trimIndent()
        val r = LibraCsv.parse(csv, ZoneId.of("Europe/Berlin"))
        assertEquals(4, r.rawRows)
        assertEquals(3, r.measurements.size)
        assertEquals(1516384380000L, r.measurements.first().timestampMs) // 2018-01-19T17:53Z kept, midnight dropped
    }

    @Test fun fitFileHasValidCrc() {
        val bytes = FitWeightWriter.write(listOf(Measurement(timestampMs = 1_760_000_000_000L, weightKg = 63.4, fatPercent = 30.1)))
        assertEquals(".FIT", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(0, FitWeightWriter.crc16(bytes.copyOfRange(0, 14)))
        assertEquals(0, FitWeightWriter.crc16(bytes))
    }

    @Test fun assemblerMergesImpedanceIntoSameWeighIn() {
        val comp = BodyComposition(UserProfile(false, 1982, 3, 168.0), 44)
        val fn = { w: Double, i: Int -> comp.compute(w, i) }
        val now = 1_800_000_000_000L
        val first = MeasurementAssembler.decide(MiScaleFrame.parse(frame(0x20, 0, 12770))!!, now, emptyList(), fn)
        val saved = (first as MeasurementAssembler.Action.Insert).measurement.copy(id = 1)
        val second = MeasurementAssembler.decide(MiScaleFrame.parse(frame(0x22, 480, 12770))!!, now + 4000, listOf(saved), fn)
        assertTrue(second is MeasurementAssembler.Action.AddImpedance)
        val updated = (second as MeasurementAssembler.Action.AddImpedance).updated
        assertEquals(MeasurementAssembler.Action.Ignore,
            MeasurementAssembler.decide(MiScaleFrame.parse(frame(0x22, 480, 12770))!!, now + 9000, listOf(updated), fn))
    }

    // Real exports start with a byte-order mark.
    private val zeppCsv = "\uFEFF" + """
        time,weight,height,bmi,fatRate,bodyWaterRate,boneMass,metabolism,muscleRate,visceralFat
        2026-10-08 04:38:28+0000,64.4,170.0,22.2,32.968544,47.860462,2.5832634,1181.0,40.584995,6.0
        2026-10-08 05:00:00+0000,80.1,185.0,23.4,0.0,0.0,0.0,0.0,0.0,0.0
        2026-10-09 12:43:02+0000,65.5,170.0,22.6,null,null,null,null,null,null
        2026-10-09 12:44:07+0000,65.1,170.0,22.5,33.54532,47.448643,2.5881531,1189.0,40.673843,6.0
    """.trimIndent()

    @Test fun zeppKeepsOnlyOwnRowsAndCollapsesRepeats() {
        val r = ZeppCsv.parse(zeppCsv, heightCm = 170.0)
        assertEquals(1, r.otherPeopleRows)
        assertEquals(1, r.collapsedRepeats)
        assertEquals(2, r.measurements.size)
        val last = r.measurements.last()
        assertEquals(65.1, last.weightKg, 1e-9)       // the reading with composition wins
        assertEquals(33.54532, last.fatPercent!!, 1e-9)
        assertEquals(6.0, last.visceralFat!!, 1e-9)
    }

    @Test fun mergeReplacesLibraCopyAndSkipsDuplicates() {
        val zone = ZoneId.of("Europe/Berlin")
        val libra = Measurement(id = 7, timestampMs = 1_791_434_308_000L, weightKg = 64.4,
            source = Measurement.Source.LIBRA, scaleKey = "libra-1")             // 2026-10-08T04:38:28Z
        val zepp = ZeppCsv.parse(zeppCsv, 170.0).measurements
        val ops = HistoryMerge.plan(listOf(libra), zepp + zepp, zone)
        val replaced = ops.filterIsInstance<HistoryMerge.Op.Replace>()
        assertEquals(1, replaced.size)
        assertEquals(7L, replaced[0].updated.id)
        assertEquals(Measurement.Source.ZEPP, replaced[0].updated.source)
        assertEquals(1, ops.count { it is HistoryMerge.Op.Insert })
        assertEquals(2, ops.count { it is HistoryMerge.Op.Skip })
    }
}
