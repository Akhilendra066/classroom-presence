package com.classroompresence.core

object BeaconParser {
    fun parse(payload: ByteArray?, roomId: String, beacons: List<BeaconIdentity>): BeaconIdentity? {
        if (payload == null) return null
        val value = payload.toString(Charsets.UTF_8)
        val parts = value.split('|')
        if (parts.size != 3 || parts[0] != "CP1" || parts[1] != roomId) return null
        val number = parts[2].toIntOrNull() ?: return null
        if (parts[2] != number.toString()) return null
        return beacons.singleOrNull { it.number == number }
    }
}

class PresenceEngine {
    /** Compact regular uploads; calibration raw data stays local. Preserves the classifier's fresh medians. */
    fun compact(batch: ScanBatch, config: PresenceConfig, beacons: List<BeaconIdentity>): ScanBatch {
        val result = evaluate(batch, config, beacons)
        fun scan(source: TechnologyScan, evidence: TechnologyEvidence): TechnologyScan = source.copy(
            observations = evidence.medians.map { (id, rssi) ->
                val freshest = source.observations.filter { it.espId == id && it.rssiDbm in -127..-1 &&
                    batch.evaluatedElapsedMs - it.elapsedMs in 0..config.maxAgeMs }.maxOf { it.elapsedMs }
                Observation(id, rssi, freshest)
            }
        )
        return batch.copy(wifi = scan(batch.wifi, result.wifi), ble = scan(batch.ble, result.ble))
    }

    fun evaluate(batch: ScanBatch, config: PresenceConfig, beacons: List<BeaconIdentity>): PresenceResult {
        config.validate(beacons.size)
        require(beacons.map { it.espId }.distinct().size == beacons.size)
        val ids = beacons.map { it.espId }.toSet()
        fun evidence(scan: TechnologyScan, inside: Int, outside: Int): TechnologyEvidence {
            val values = scan.observations.filter {
                it.espId in ids && it.rssiDbm in -127..-1 &&
                    batch.evaluatedElapsedMs - it.elapsedMs in 0..config.maxAgeMs
            }.groupBy { it.espId }.mapValues { (_, obs) ->
                val sorted = obs.map { it.rssiDbm }.sorted()
                if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
            return TechnologyEvidence(values.size, values.values.count { it >= inside },
                values.values.count { it <= outside },
                if (values.isEmpty()) null else values.values.max() - values.values.min(), values)
        }
        val wifi = evidence(batch.wifi, config.wifiInsideDbm, config.wifiOutsideDbm)
        val ble = evidence(batch.ble, config.bleInsideDbm, config.bleOutsideDbm)
        val reasons = mutableListOf<String>()
        if (batch.wifi.outcome != ScanOutcome.SUCCESS) reasons += "WIFI_${batch.wifi.outcome}"
        if (batch.ble.outcome != ScanOutcome.SUCCESS) reasons += "BLE_${batch.ble.outcome}"
        if (wifi.detected < config.minDetected) reasons += "WIFI_TOO_FEW_FRESH_BEACONS"
        if (ble.detected < config.minDetected) reasons += "BLE_TOO_FEW_FRESH_BEACONS"
        if (reasons.isNotEmpty()) return PresenceResult(PresenceStatus.INSUFFICIENT_DATA, 0, wifi, ble, reasons)
        val wifiStrong = wifi.strong >= config.minStrong
        val bleStrong = ble.strong >= config.minStrong
        val wifiWeak = wifi.weak >= config.minWeak
        val bleWeak = ble.weak >= config.minWeak
        val balanced = wifi.spreadDb!! <= config.maxSpreadDb && ble.spreadDb!! <= config.maxSpreadDb
        val status = when {
            wifiWeak && bleWeak -> PresenceStatus.OUTSIDE
            wifiStrong && bleStrong && balanced -> PresenceStatus.INSIDE
            else -> PresenceStatus.UNCERTAIN
        }
        reasons += "WIFI_STRONG_${wifi.strong}_WEAK_${wifi.weak}"
        reasons += "BLE_STRONG_${ble.strong}_WEAK_${ble.weak}"
        if (!balanced) reasons += "SIGNAL_IMBALANCE"
        reasons += when (status) {
            PresenceStatus.INSIDE -> "BOTH_TECHNOLOGIES_SUPPORT_INSIDE"
            PresenceStatus.OUTSIDE -> "BOTH_TECHNOLOGIES_SUPPORT_OUTSIDE"
            else -> "BOUNDARY_OR_CONFLICTING_EVIDENCE"
        }
        return PresenceResult(status, wifi.strong + ble.strong, wifi, ble, reasons)
    }
}

object AttendanceAggregator {
    fun aggregate(checkpoints: List<Checkpoint>, config: PresenceConfig): AttendanceSummary {
        config.validate()
        require(checkpoints.map { it.slot }.distinct().size == checkpoints.size) { "Duplicate slots" }
        require(checkpoints.all { it.slot in 0 until config.totalSlots && it.configVersion == config.version })
        require(checkpoints.map { it.sessionId to it.uid }.distinct().size <= 1) { "Mixed participants or sessions" }
        val inside = checkpoints.count { it.result.status == PresenceStatus.INSIDE }
        val outside = checkpoints.count { it.result.status == PresenceStatus.OUTSIDE }
        val uncertain = checkpoints.count { it.result.status == PresenceStatus.UNCERTAIN }
        val valid = inside + outside + uncertain
        val outcome = when {
            valid < config.minValid -> AttendanceOutcome.INSUFFICIENT_COVERAGE
            config.minInsideSlots > 0 && inside >= config.minInsideSlots -> AttendanceOutcome.ELIGIBLE
            config.minInsideSlots == 0 && inside * 100 >= config.insidePercent * valid -> AttendanceOutcome.ELIGIBLE
            else -> AttendanceOutcome.BELOW_THRESHOLD
        }
        return AttendanceSummary(outcome, inside, outside, uncertain, valid, config.totalSlots,
            config.totalSlots - valid, if (valid == 0) 0 else inside * 100 / valid)
    }
}

/** Display only. Never use this state in attendance aggregation. */
class DisplayHysteresis(private val window: Int = 5) {
    private val history = ArrayDeque<PresenceStatus>()
    private var display = PresenceStatus.UNCERTAIN
    fun update(status: PresenceStatus): PresenceStatus {
        if (status == PresenceStatus.INSUFFICIENT_DATA) { reset(); return status }
        history.addLast(status)
        if (history.size > window) history.removeFirst()
        val inside = history.count { it == PresenceStatus.INSIDE }
        display = when {
            display == PresenceStatus.INSIDE && inside * 100 > history.size * 60 -> display
            inside * 100 >= history.size * 80 -> PresenceStatus.INSIDE
            status == PresenceStatus.OUTSIDE -> PresenceStatus.OUTSIDE
            else -> PresenceStatus.UNCERTAIN
        }
        return display
    }
    fun reset() { history.clear(); display = PresenceStatus.UNCERTAIN }
}

object Simulation {
    fun batch(wifi: List<Int?>, ble: List<Int?>, at: Long = 1_000): ScanBatch {
        fun scan(values: List<Int?>) = TechnologyScan(ScanOutcome.SUCCESS,
            values.mapIndexedNotNull { i, value -> value?.let { Observation("CLASSROOM_ESP_${i + 1}", it, at) } })
        return ScanBatch(scan(wifi), scan(ble), at)
    }
}
