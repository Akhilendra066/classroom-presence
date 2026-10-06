package com.classroompresence.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.classroompresence.core.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class RadioScanner(context: Context) {
    private val app = context.applicationContext
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    fun readiness(): List<String> = buildList {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("Allow precise location for Wi-Fi and classroom detection.")
        if (!granted(Manifest.permission.BLUETOOTH_SCAN)) add("Allow Nearby devices to scan BLE beacons.")
        if (!app.getSystemService(LocationManager::class.java).isLocationEnabled) add("Enable Location in Android settings.")
        if (!app.getSystemService(WifiManager::class.java).isWifiEnabled) add("Enable Wi-Fi. You do not need to connect to an ESP network.")
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) add("Allow Nearby devices to check Bluetooth readiness.")
        else if (!bluetoothEnabled()) add("Enable Bluetooth in Android settings.")
    }
    @SuppressLint("MissingPermission")
    private fun bluetoothEnabled(): Boolean = app.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    suspend fun collect(roomId: String, beacons: List<BeaconIdentity>, durationMs: Long): ScanBatch = coroutineScope {
        val wifi = async { scanWifi(beacons) }
        val ble = async { scanBle(roomId, beacons, durationMs) }
        ScanBatch(wifi.await(), ble.await(), SystemClock.elapsedRealtime())
    }
    @SuppressLint("MissingPermission")
    private suspend fun scanWifi(beacons: List<BeaconIdentity>): TechnologyScan {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return TechnologyScan(ScanOutcome.PERMISSION_DENIED)
        val manager = app.getSystemService(WifiManager::class.java)
        if (!manager.isWifiEnabled || !app.getSystemService(LocationManager::class.java).isLocationEnabled) return TechnologyScan(ScanOutcome.DISABLED)
        val requestedAt = SystemClock.elapsedRealtime()
        return try {
            withTimeoutOrNull(28_000) {
                suspendCancellableCoroutine { cont ->
                    var registered = false
                    lateinit var receiver: BroadcastReceiver
                    fun cleanup() { if (registered) { registered = false; runCatching { app.unregisterReceiver(receiver) } } }
                    receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                            val scan = try {
                                if (!updated) TechnologyScan(ScanOutcome.FAILED, detail = "Platform returned cached results")
                                else {
                                    val readings = manager.scanResults.mapNotNull { r ->
                                        val beacon = beacons.singleOrNull { it.wifiSsid == r.SSID } ?: return@mapNotNull null
                                        val elapsed = r.timestamp / 1000L
                                        // Only observations taken during this acquisition, not previous scans.
                                        if (elapsed < requestedAt) null else Observation(beacon.espId, r.level, elapsed)
                                    }
                                    TechnologyScan(ScanOutcome.SUCCESS, readings)
                                }
                            } catch (_: SecurityException) { TechnologyScan(ScanOutcome.PERMISSION_DENIED) }
                            cleanup()
                            if (cont.isActive) cont.resume(scan)
                        }
                    }
                    try {
                        ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
                        registered = true
                        cont.invokeOnCancellation { cleanup() }
                        if (!manager.startScan()) {
                            cleanup()
                            if (cont.isActive) cont.resume(TechnologyScan(ScanOutcome.FAILED, detail = "Wi-Fi request rejected or throttled"))
                        }
                    } catch (_: SecurityException) {
                        cleanup(); if (cont.isActive) cont.resume(TechnologyScan(ScanOutcome.PERMISSION_DENIED))
                    }
                }
            } ?: TechnologyScan(ScanOutcome.TIMEOUT)
        } catch (_: SecurityException) { TechnologyScan(ScanOutcome.PERMISSION_DENIED) }
    }
    @SuppressLint("MissingPermission")
    private suspend fun scanBle(roomId: String, beacons: List<BeaconIdentity>, durationMs: Long): TechnologyScan {
        if (!granted(Manifest.permission.BLUETOOTH_SCAN) || !granted(Manifest.permission.ACCESS_FINE_LOCATION) || !granted(Manifest.permission.BLUETOOTH_CONNECT)) return TechnologyScan(ScanOutcome.PERMISSION_DENIED)
        val scanner = try { app.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner }
            catch (_: SecurityException) { return TechnologyScan(ScanOutcome.PERMISSION_DENIED) }
            ?: return TechnologyScan(ScanOutcome.DISABLED)
        val observations = mutableListOf<Observation>()
        var failure: Int? = null
        val lock = Any()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val beacon = BeaconParser.parse(result.scanRecord?.getManufacturerSpecificData(0xFFFF), roomId, beacons) ?: return
                synchronized(lock) {
                    if (observations.size < 5000) observations += Observation(beacon.espId, result.rssi, result.timestampNanos / 1_000_000)
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) { synchronized(lock) { failure = errorCode } }
        }
        return try {
            val filter = ScanFilter.Builder().setManufacturerData(0xFFFF, "CP1|".toByteArray()).build()
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            delay(durationMs)
            synchronized(lock) {
                if (failure != null) TechnologyScan(ScanOutcome.FAILED, detail = "BLE error $failure")
                else TechnologyScan(ScanOutcome.SUCCESS, observations.toList())
            }
        } catch (_: SecurityException) { TechnologyScan(ScanOutcome.PERMISSION_DENIED) }
        catch (e: IllegalStateException) { TechnologyScan(ScanOutcome.FAILED, detail = e.message.orEmpty()) }
        finally { runCatching { scanner.stopScan(callback) } }
    }
}
