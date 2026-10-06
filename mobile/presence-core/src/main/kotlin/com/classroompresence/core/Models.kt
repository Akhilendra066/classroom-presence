package com.classroompresence.core

import kotlinx.serialization.Serializable

@Serializable enum class PresenceStatus { INSIDE, OUTSIDE, UNCERTAIN, INSUFFICIENT_DATA }
@Serializable enum class ScanOutcome { SUCCESS, DISABLED, PERMISSION_DENIED, FAILED, TIMEOUT }
@Serializable data class BeaconIdentity(val espId: String, val number: Int, val wifiSsid: String)
@Serializable data class PresenceConfig(
    val version: Int = 1,
    val calibrated: Boolean = false,
    val wifiInsideDbm: Int = -72,
    val bleInsideDbm: Int = -75,
    val wifiOutsideDbm: Int = -82,
    val bleOutsideDbm: Int = -85,
    val minDetected: Int = 3,
    val minStrong: Int = 3,
    val minWeak: Int = 3,
    val maxSpreadDb: Int = 30,
    val maxAgeMs: Long = 35_000,
    val bleDurationMs: Long = 25_000,
    val intervalMs: Long = 300_000,
    val totalSlots: Int = 12,
    val minValid: Int = 8,
    val insidePercent: Int = 60,
    val minInsideSlots: Int = 0
) {
    fun validate(beaconCount: Int = 4) {
        require(version > 0)
        require(wifiInsideDbm in -127..-1 && bleInsideDbm in -127..-1)
        require(wifiOutsideDbm in -127 until wifiInsideDbm && bleOutsideDbm in -127 until bleInsideDbm)
        require(minDetected in 1..beaconCount && minStrong in minDetected..beaconCount && minWeak in minDetected..beaconCount)
        require(maxSpreadDb in 1..100 && maxAgeMs in 1_000..60_000)
        require(bleDurationMs in 1_000..30_000 && intervalMs > bleDurationMs)
        require(totalSlots in 1..40 && minValid in 1..totalSlots && insidePercent in 1..100)
        require(minInsideSlots in 0..totalSlots)
    }
}
@Serializable data class Observation(val espId: String, val rssiDbm: Int, val elapsedMs: Long)
@Serializable data class TechnologyScan(val outcome: ScanOutcome, val observations: List<Observation> = emptyList(), val detail: String = "")
@Serializable data class ScanBatch(val wifi: TechnologyScan, val ble: TechnologyScan, val evaluatedElapsedMs: Long)
@Serializable data class TechnologyEvidence(val detected: Int, val strong: Int, val weak: Int, val spreadDb: Int?, val medians: Map<String, Int>)
@Serializable data class PresenceResult(
    val status: PresenceStatus,
    val score: Int,
    val wifi: TechnologyEvidence,
    val ble: TechnologyEvidence,
    val reasons: List<String>
)
@Serializable data class RosterMember(val uid: String, val name: String, val email: String)
@Serializable data class ClassInfo(val id: String, val title: String, val roomId: String, val teacherUids: List<String> = emptyList())
@Serializable data class ClassSession(
    val id: String, val classId: String, val title: String, val roomId: String,
    val startMs: Long, val endMs: Long, val config: PresenceConfig,
    val beacons: List<BeaconIdentity>, val state: String = "ACTIVE", val demo: Boolean = false
)
@Serializable data class Checkpoint(
    val sessionId: String, val uid: String, val slot: Int, val capturedAtMs: Long,
    val configVersion: Int, val batch: ScanBatch, val result: PresenceResult,
    val algorithmVersion: Int = 1, val demo: Boolean = false
)
@Serializable enum class AttendanceOutcome { ELIGIBLE, BELOW_THRESHOLD, INSUFFICIENT_COVERAGE }
@Serializable data class AttendanceSummary(
    val outcome: AttendanceOutcome, val inside: Int, val outside: Int, val uncertain: Int,
    val valid: Int, val expected: Int, val missing: Int, val insidePercentage: Int
)
@Serializable data class AttendanceRecord(val uid: String, val summary: AttendanceSummary, val final: Boolean = false, val overrideOutcome: String? = null, val updatedAtMs: Long = 0)
@Serializable data class Account(val uid: String, val name: String, val role: String, val demo: Boolean = false)
object Defaults {
    val beacons = (1..4).map { BeaconIdentity("CLASSROOM_ESP_$it", it, "CP_ROOM_A101_ESP_$it") }
    val config = PresenceConfig()
}

/** Adaptive sampling: about one checkpoint per three minutes, at least five. */
data class SessionTiming(val durationMinutes: Int, val minimumPresenceMinutes: Int) {
    val checkpointCount: Int get() = maxOf(5, (durationMinutes + 2) / 3)
    val intervalMs: Long get() = durationMinutes * 60_000L / checkpointCount
    val requiredInsideSlots: Int get() = (minimumPresenceMinutes * checkpointCount + durationMinutes - 1) / durationMinutes
    val effectiveMinimumMs: Long get() = (requiredInsideSlots * durationMinutes * 60_000L + checkpointCount - 1) / checkpointCount
    fun validate() {
        require(durationMinutes in 15..120 && durationMinutes % 5 == 0) { "Class duration must be 15–120 minutes in steps of 5." }
        require(minimumPresenceMinutes in 1..durationMinutes) { "Minimum presence must be between 1 minute and the class duration." }
    }
}

fun sampleDurationLabel(ms: Long): String {
    val seconds = (ms + 999) / 1000
    return if (seconds % 60 == 0L) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} sec"
}
