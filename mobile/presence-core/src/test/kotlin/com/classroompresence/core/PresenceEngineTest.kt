package com.classroompresence.core

import java.io.File
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PresenceEngineTest {
    private val engine = PresenceEngine()
    @Serializable data class Fixture(val name: String, val wifi: List<Int?>, val ble: List<Int?>, val expected: PresenceStatus)
    @Test fun sharedFixtures() {
        val cases = Json.decodeFromString<List<Fixture>>(File(System.getProperty("fixtures")).readText())
        cases.forEach { assertEquals(it.expected, engine.evaluate(Simulation.batch(it.wifi, it.ble), Defaults.config, Defaults.beacons).status, it.name) }
    }
    @Test fun staleAndFutureDataIsNotEvidence() {
        val batch = Simulation.batch(List(4) { -55 }, List(4) { -55 })
        for (now in listOf(999L, 36_001L)) assertEquals(PresenceStatus.INSUFFICIENT_DATA, engine.evaluate(batch.copy(evaluatedElapsedMs = now), Defaults.config, Defaults.beacons).status)
    }
    @Test fun failuresAreNotOutside() {
        val batch = Simulation.batch(List(4) { -90 }, List(4) { -95 })
        ScanOutcome.entries.filter { it != ScanOutcome.SUCCESS }.forEach {
            assertEquals(PresenceStatus.INSUFFICIENT_DATA, engine.evaluate(batch.copy(wifi = batch.wifi.copy(outcome = it)), Defaults.config, Defaults.beacons).status)
        }
    }
    @Test fun unknownAndInvalidReadingsAreRejected() {
        val scan = TechnologyScan(ScanOutcome.SUCCESS, listOf(Observation("rogue", -40, 1_000), Observation("CLASSROOM_ESP_1", 127, 1_000)))
        assertEquals(PresenceStatus.INSUFFICIENT_DATA, engine.evaluate(ScanBatch(scan, scan, 1_000), Defaults.config, Defaults.beacons).status)
    }
    @Test fun medianSuppressesSingleSpike() {
        val batch = Simulation.batch(List(4) { -90 }, List(4) { -95 })
        val noisy = batch.wifi.copy(observations = batch.wifi.observations.flatMap { listOf(it, it, it.copy(rssiDbm = -30)) })
        assertEquals(PresenceStatus.OUTSIDE, engine.evaluate(batch.copy(wifi = noisy), Defaults.config, Defaults.beacons).status)
    }
    @Test fun parsesFirmwarePayloadStrictly() {
        assertEquals("CLASSROOM_ESP_1", BeaconParser.parse("CP1|ROOM_A101|1".toByteArray(), "ROOM_A101", Defaults.beacons)?.espId)
        listOf("CP2|ROOM_A101|1", "CP1|ROOM_A102|1", "CP1|ROOM_A101|9", "CP1|ROOM_A101|01", "CP1|ROOM_A101|1|extra").forEach { assertNull(BeaconParser.parse(it.toByteArray(), "ROOM_A101", Defaults.beacons)) }
    }
    private fun checkpoints(inside: Int, outside: Int, uncertain: Int = 0): List<Checkpoint> =
        (List(inside) { PresenceStatus.INSIDE } + List(outside) { PresenceStatus.OUTSIDE } + List(uncertain) { PresenceStatus.UNCERTAIN }).mapIndexed { i, status ->
            val batch = Simulation.batch(List(4) { -55 }, List(4) { -55 })
            Checkpoint("s", "u", i, 1_000, 1, batch, engine.evaluate(batch, Defaults.config, Defaults.beacons).copy(status = status))
        }
    @Test fun attendanceBoundariesAndCoverage() {
        assertEquals(AttendanceOutcome.ELIGIBLE, AttendanceAggregator.aggregate(checkpoints(8,4), Defaults.config).outcome)
        assertEquals(AttendanceOutcome.BELOW_THRESHOLD, AttendanceAggregator.aggregate(checkpoints(7,5), Defaults.config).outcome)
        assertEquals(AttendanceOutcome.ELIGIBLE, AttendanceAggregator.aggregate(checkpoints(5,3), Defaults.config).outcome)
        assertEquals(AttendanceOutcome.INSUFFICIENT_COVERAGE, AttendanceAggregator.aggregate(checkpoints(7,0), Defaults.config).outcome)
        assertEquals(AttendanceOutcome.BELOW_THRESHOLD, AttendanceAggregator.aggregate(checkpoints(5,3,4), Defaults.config).outcome)
        assertEquals(AttendanceOutcome.INSUFFICIENT_COVERAGE, AttendanceAggregator.aggregate(emptyList(), Defaults.config).outcome)
    }
    @Test fun duplicateOrMixedEvidenceFailsClosed() {
        val points = checkpoints(8,4)
        assertFailsWith<IllegalArgumentException> { AttendanceAggregator.aggregate(points + points.first(), Defaults.config) }
        assertFailsWith<IllegalArgumentException> { AttendanceAggregator.aggregate(points.mapIndexed { i, p -> if(i == 0) p.copy(uid="other") else p }, Defaults.config) }
    }
    @Test fun hysteresisDoesNotSurviveMissingEvidence() {
        val display = DisplayHysteresis()
        assertEquals(PresenceStatus.INSIDE, display.update(PresenceStatus.INSIDE))
        assertEquals(PresenceStatus.INSUFFICIENT_DATA, display.update(PresenceStatus.INSUFFICIENT_DATA))
        assertEquals(PresenceStatus.OUTSIDE, display.update(PresenceStatus.OUTSIDE))
    }
    @Test fun compactionPreservesFreshEvidenceAndClassification() {
        val base = Simulation.batch(List(4) { -90 }, List(4) { -95 })
        val noisy = base.copy(wifi = base.wifi.copy(observations = base.wifi.observations.flatMap { listOf(it, it, it.copy(rssiDbm = -30), it.copy(rssiDbm = -40, elapsedMs = 0)) }), evaluatedElapsedMs = 35_001)
        val compact = engine.compact(noisy, Defaults.config, Defaults.beacons)
        assertEquals(engine.evaluate(noisy, Defaults.config, Defaults.beacons), engine.evaluate(compact, Defaults.config, Defaults.beacons))
        assertTrue(compact.wifi.observations.size <= 4)
    }
    @Test fun invalidConfigurationRejected() {
        assertFailsWith<IllegalArgumentException> { Defaults.config.copy(wifiOutsideDbm=-60).validate() }
        assertFailsWith<IllegalArgumentException> { Defaults.config.copy(minValid=13).validate() }
    }
    @Test fun teacherMinimumUsesAbsoluteInsideEvidence() {
        val config = Defaults.config.copy(totalSlots = 6, minValid = 4, insidePercent = 1, minInsideSlots = 4)
        assertEquals(AttendanceOutcome.ELIGIBLE, AttendanceAggregator.aggregate(checkpoints(4, 0), config).outcome)
        assertEquals(AttendanceOutcome.INSUFFICIENT_COVERAGE, AttendanceAggregator.aggregate(checkpoints(3, 0), config).outcome)
        assertEquals(AttendanceOutcome.BELOW_THRESHOLD, AttendanceAggregator.aggregate(checkpoints(3, 1, 2), config).outcome)
        assertEquals(AttendanceOutcome.INSUFFICIENT_COVERAGE, AttendanceAggregator.aggregate(emptyList(), config).outcome)
        val missing = checkpoints(4, 0).map { it.copy(result = it.result.copy(status = PresenceStatus.INSUFFICIENT_DATA)) }
        assertEquals(0, AttendanceAggregator.aggregate(missing, config).valid)
        assertEquals(AttendanceOutcome.INSUFFICIENT_COVERAGE, AttendanceAggregator.aggregate(missing, config).outcome)
    }
    @Test fun teacherTimingValidatesAndRoundsUp() {
        SessionTiming(30, 17).validate()
        assertEquals(6, SessionTiming(30, 17).requiredInsideSlots)
        assertEquals(1_080_000L, SessionTiming(30, 17).effectiveMinimumMs)
        assertEquals(40, SessionTiming(120, 120).requiredInsideSlots)
        listOf(SessionTiming(10, 5), SessionTiming(32, 20), SessionTiming(125, 20), SessionTiming(30, 31), SessionTiming(30, 0))
            .forEach { assertFailsWith<IllegalArgumentException> { it.validate() } }
        assertFailsWith<IllegalArgumentException> { Defaults.config.copy(minInsideSlots = 13).validate() }
    }
    @Test fun oldRoomConfigurationsRetainLegacyAttendanceRules() {
        assertEquals(0, Json.decodeFromString<PresenceConfig>("{}").minInsideSlots)
        assertEquals(AttendanceOutcome.ELIGIBLE, AttendanceAggregator.aggregate(checkpoints(5, 3), Defaults.config).outcome)
    }

    @Test fun adaptiveCheckpointsScaleWithDurationAndRemainWithinClass() {
        for (duration in 15..120 step 5) {
            val timing = SessionTiming(duration, duration)
            timing.validate()
            assertTrue(timing.checkpointCount in 5..40)
            assertEquals(timing.checkpointCount, timing.requiredInsideSlots)
            assertTrue((timing.checkpointCount - 1) * timing.intervalMs + 30_000 < duration * 60_000L)
            assertEquals(duration * 60_000L, timing.effectiveMinimumMs)
            for (minimum in 1..duration) {
                val policy = SessionTiming(duration, minimum)
                assertTrue(policy.requiredInsideSlots in 1..policy.checkpointCount)
                assertTrue(policy.effectiveMinimumMs >= minimum * 60_000L)
                assertTrue(policy.effectiveMinimumMs <= duration * 60_000L)
            }
        }
        assertEquals(5, SessionTiming(15, 10).checkpointCount)
        assertEquals(20, SessionTiming(60, 40).checkpointCount)
        assertEquals(180_000L, SessionTiming(60, 40).intervalMs)
        assertEquals(14, SessionTiming(60, 40).requiredInsideSlots)
    }

}
