package com.classroompresence.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.classroompresence.core.*
import com.classroompresence.data.*
import com.classroompresence.scanner.RadioScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

 data class MonitorState(val sessionId: String? = null, val running: Boolean = false, val message: String = "Monitoring stopped", val display: PresenceStatus? = null)
class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionWatcher: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("sessionId") ?: return START_NOT_STICKY.also { stopSelf() }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitoring", "Class monitoring", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, 101, notification("Preparing checkpoint collection"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: SecurityException) { mutable.value = MonitorState(message = "Location permission is required to start monitoring."); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            try {
                val repo = PresenceRepository(this@MonitoringService)
                val who = repo.account ?: error("Sign in first")
                check(who.role == "STUDENT")
                val session = repo.session(id)
                check(!session.demo && !who.demo) { "Sign in with your institution account to monitor this session." }
                check(session.state == "ACTIVE" && session.endMs > System.currentTimeMillis()) { "This session has ended." }
                check(session.config.calibrated) { "Teacher must calibrate this room first." }
                val scanner = RadioScanner(this@MonitoringService)
                check(scanner.readiness().isEmpty()) { scanner.readiness().joinToString(" ") }
                val startElapsed = SystemClock.elapsedRealtime() + session.startMs - System.currentTimeMillis()
                val endElapsed = startElapsed + session.endMs - session.startMs
                val remaining = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "classroom:monitoring").also { it.acquire(remaining + 10_000) }
                mutable.value = MonitorState(id, true, "Monitoring classroom beacons")
                sessionWatcher = scope.launch {
                    while (isActive) {
                        delay(15_000)
                        // A network outage must not stop cached acquisition.
                        runCatching { withTimeout(10_000) { repo.sessions() } }
                        if (repo.session(id).state != "ACTIVE") {
                            mutable.value = MonitorState(id, false, "Teacher ended this class. Saved uploads remain queued.")
                            job?.cancel(); stopSelf(); break
                        }
                    }
                }
                val hysteresis = DisplayHysteresis()
                val existing = repo.dao.checkpoints(who.uid, id).map { it.slot }.toSet()
                for (slot in 0 until session.config.totalSlots) {
                    if (slot in existing) continue
                    val due = startElapsed + slot * session.config.intervalMs
                    val now = SystemClock.elapsedRealtime()
                    val tolerance = minOf(30_000L, session.config.intervalMs / 2)
                    if (now > due + tolerance) continue // Do not backfill missed checkpoints.
                    delay((due - now).coerceAtLeast(0))
                    if (SystemClock.elapsedRealtime() >= endElapsed) break
                    // Re-read cached session; online teacher end is refreshed by the UI.
                    val latest = repo.session(id)
                    if (latest.state != "ACTIVE") break
                    mutable.value = mutable.value.copy(message = "Collecting checkpoint ${slot + 1}/${session.config.totalSlots}")
                    val rawBatch = scanner.collect(session.roomId, session.beacons, session.config.bleDurationMs)
                    if (SystemClock.elapsedRealtime() >= endElapsed) break
                    val batch = PresenceEngine().compact(rawBatch, session.config, session.beacons)
                    val result = PresenceEngine().evaluate(batch, session.config, session.beacons)
                    val point = Checkpoint(id, who.uid, slot, session.startMs + batch.evaluatedElapsedMs - startElapsed,
                        session.config.version, batch, result)
                    repo.saveCheckpoint(point)
                    SyncWorker.enqueue(this@MonitoringService)
                    val display = hysteresis.update(result.status)
                    mutable.value = MonitorState(id, true, "Checkpoint ${slot + 1} saved · ${result.status}", display)
                    manager.notify(101, notification("${result.status} · checkpoint ${slot + 1}/${session.config.totalSlots}"))
                }
                delay((endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                mutable.value = MonitorState(id, false, "Session collection complete. Pending uploads will retry.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = MonitorState(id, false, e.message ?: "Monitoring failed") }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "monitoring").setSmallIcon(R.drawable.ic_classroom)
            .setContentTitle("Classroom presence is active").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(0, "Stop monitoring", stop).build()
    }
    override fun onDestroy() {
        sessionWatcher?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (mutable.value.running) mutable.value = mutable.value.copy(running = false, message = "Monitoring stopped; missing slots remain missing.")
        super.onDestroy()
    }
    companion object {
        private val mutable = MutableStateFlow(MonitorState())
        val state = mutable.asStateFlow()
    }
}
