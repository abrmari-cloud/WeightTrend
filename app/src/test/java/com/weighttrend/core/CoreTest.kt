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

    @Test fun metricSeriesSkipsMissingValuesAndComputesBmi() {
        val p = UserProfile(false, 1982, 7, 170.0)
        val a = Measurement(timestampMs = 1_791_446_400_000L, weightKg = 64.4, fatPercent = 33.0, muscleKg = 40.6) // 10:00 Berlin
        val b = Measurement(timestampMs = 1_791_446_400_000L + 86_400_000L, weightKg = 64.0)
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(1, Metric.FAT.series(listOf(a, b), p, berlin).size)
        assertEquals(2, Metric.WEIGHT.series(listOf(a, b), p, berlin).size)
        assertEquals(22.28, Metric.BMI.of(a, p)!!, 0.01)
        assertNull(Metric.BMI.of(a, null))
    }

    @Test fun timeAxisUsesRoundDates() {
        val zone = ZoneId.of("Europe/Berlin")
        val to = java.time.ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val month = TimeAxis.ticks(to - 30 * 86_400_000L, to, zone, 5).map { it.label }
        assertEquals(listOf("14 сент", "21 сент", "28 сент", "5 окт"), month)
        val year = TimeAxis.ticks(to - 365 * 86_400_000L, to, zone, 4).map { it.label }
        assertEquals(listOf("янв 26", "апр", "июль", "окт"), year)
    }

    // ---------------------------------------------------------------- 0.4

    private val zone = ZoneId.of("Europe/Berlin")
    private val day = 86_400_000L
    private val now = java.time.ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, zone).toInstant().toEpochMilli()

    /** Daily trend points over [days] days changing by [kgPerWeek]. */
    private fun line(days: Int, start: Double, kgPerWeek: Double, endMs: Long = now) =
        (0 until days).map { i -> (endMs - (days - 1 - i) * day) to (start + kgPerWeek / 7 * i) }

    @Test fun impedanceInverseRoundTrips() {
        val calc = BodyComposition(UserProfile(false, 1982, 7, 170.0), 44)
        val r = calc.compute(65.1, 480)
        val z = calc.impedanceFromFat(65.1, r.fatPercent)!!
        // fat is rounded to 0.1 %, and 0.1 % of fat corresponds to ~10 ohm here
        assertTrue("got $z", kotlin.math.abs(z - 480) <= 6)
    }

    @Test fun bmrUsesLeanMass() {
        assertEquals(370 + 21.6 * 43.3, BodyComposition.bmrKatchMcArdle(65.1, 33.4869), 0.5)
    }

    @Test fun morningIsStandard() {
        val morning = Measurement(timestampMs = now, weightKg = 64.0, fatPercent = 33.0)          // 07:00
        val evening = morning.copy(timestampMs = now + 13 * 3_600_000L, fatPercent = 33.0)        // 20:00
        assertTrue(Conditions.isStandard(morning, zone))
        assertFalse(Conditions.isStandard(evening, zone))
        assertEquals(1, Metric.FAT.series(listOf(morning, evening), null, zone).size)
        assertEquals(2, Metric.WEIGHT.series(listOf(morning, evening), null, zone).size)
    }

    @Test fun energyBalanceFromSlope() {
        val est = EnergyBalance.estimate(line(28, 64.0, -0.35), now)!!
        assertEquals(-0.35, est.kgPerWeek, 0.001)
        assertEquals(-385.0, est.kcalPerDay, 1.0)
        assertNull(EnergyBalance.estimate(line(10, 64.0, -0.35), now))   // span too short
    }

    @Test fun coachVerdicts() {
        val goal = Goal(59.5, null)  // default pace 0.5 %/wk ≈ −350 kcal at 64 kg
        assertEquals(Coach.Kind.NO_DATA, Coach.advise(line(3, 64.0, 0.0), goal, now, zone).kind)
        assertEquals(Coach.Kind.ON_TRACK, Coach.advise(line(28, 64.3, -0.33), goal, now, zone).kind)
        assertEquals(Coach.Kind.TOO_SLOW, Coach.advise(line(28, 64.1, -0.1), goal, now, zone).kind)
        assertEquals(Coach.Kind.TOO_FAST, Coach.advise(line(28, 65.0, -0.9), goal, now, zone).kind)
        assertEquals(Coach.Kind.WRONG_DIRECTION, Coach.advise(line(28, 63.6, 0.08), goal, now, zone).kind)
        assertEquals(Coach.Kind.REACHED, Coach.advise(line(28, 59.6, 0.0), goal, now, zone).kind)
        assertEquals(Coach.Kind.NO_GOAL, Coach.advise(line(28, 64.0, 0.07), null, now, zone).kind)
        // 4 weeks of loss, then 4 weeks flat
        val plateau = line(28, 63.0, -0.4, now - 28 * day) + line(28, 61.4, 0.0)
        assertEquals(Coach.Kind.PLATEAU, Coach.advise(plateau, goal, now, zone).kind)
    }

    @Test fun goalPaceAndEarliestDate() {
        val today = LocalDateOf(2026, 10, 10)
        val g = Goal(59.5, LocalDateOf(2026, 11, 10))          // 4.5 kg in 31 days
        assertTrue(Coach.requiredPacePct(64.0, g, today) > Coach.MAX_PACE_PCT)
        val safe = Coach.earliestSafeDate(64.0, g, today)        // 0.64 kg/wk → 49.2 days
        assertEquals(LocalDateOf(2026, 11, 29), safe)
    }

    @Test fun weeklySplit() {
        val weeks = (0 until 8).map { i ->
            WeeklyAnalysis.Week(LocalDateOf(2026, 8, 3).plusWeeks(i.toLong()),
                avgSteps = if (i % 2 == 0) 11000.0 else 5000.0, avgSleepHours = 7.0,
                balanceKcal = if (i % 2 == 0) -200.0 else 100.0)
        }
        val s = WeeklyAnalysis.split(weeks) { it.avgSteps }!!
        assertEquals(-300.0, s.difference, 1e-9)
        assertEquals(4, s.highWeeks)
        assertTrue(s.significant)
        assertFalse(s.narrowRange)
    }

    private fun LocalDateOf(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d)

    // ---------------------------------------------------------------- 0.5

    @Test fun weeklyBalanceUsesNeighbouringWeekAverages() {
        // Mondays 7, 14, 21 Sep 2026 (08:00), weights averaging 64.0, 64.3 (noisy), 64.2
        fun at(d: Int, kg: Double) = java.time.ZonedDateTime.of(2026, 9, d, 8, 0, 0, 0, zone).toInstant().toEpochMilli() to kg
        val w = listOf(at(7, 63.8), at(9, 64.2), at(14, 64.9), at(16, 63.7), at(21, 64.0), at(23, 64.4))
        val b = WeeklyAnalysis.balances(w, zone)
        val mid = b.getValue(LocalDateOf(2026, 9, 14))
        assertEquals((64.2 - 64.0) / 14 * 7700, mid, 1e-6)   // the noisy middle week does not dominate
        assertNull(b[LocalDateOf(2026, 9, 7)])                // no week before
    }

    @Test fun noisyDifferenceIsNotSignificant() {
        val bal = listOf(604.0, -326.0, 776.0, -1066.0, 300.0, -200.0, 500.0, -400.0)
        val weeks = bal.mapIndexed { i, b ->
            WeeklyAnalysis.Week(LocalDateOf(2026, 8, 3).plusWeeks(i.toLong()), avgSteps = 8600.0 + i * 200, balanceKcal = b)
        }
        val s = WeeklyAnalysis.split(weeks) { it.avgSteps }!!
        assertFalse(s.significant)
        assertTrue(s.narrowRange)   // 8 600–10 000 steps
    }

    @Test fun reportContainsKeySections() {
        val p = UserProfile(false, 1982, 7, 170.0)
        val ms = (0 until 40).map { i ->
            Measurement(timestampMs = now - (39 - i) * day, weightKg = 64.0 + i * 0.01, fatPercent = 33.0, waterPercent = 47.9,
                impedanceOhm = 570)
        }
        val text = ConsultationReport.build(ms, p, Goal(59.5, null), emptyList(), null, now, zone)
        assertTrue(text, text.contains("Профиль: женщина, 44 года, рост 170 см."))
        assertTrue(text, text.contains("Энергобаланс за 4 недели"))
        assertTrue(text, text.contains("базовый обмен (Катч–Макардл)"))
    }
}
