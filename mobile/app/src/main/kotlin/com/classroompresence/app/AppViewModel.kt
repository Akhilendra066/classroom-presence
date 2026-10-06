package com.classroompresence.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.classroompresence.core.*
import com.classroompresence.data.*
import com.classroompresence.scanner.RadioScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.util.UUID

 data class AppState(
    val account: Account? = null, val classes: List<ClassInfo> = emptyList(), val sessions: List<ClassSession> = emptyList(),
    val setupClass: ClassInfo? = null, val selected: ClassSession? = null, val points: List<StoredCheckpoint> = emptyList(),
    val attendance: List<AttendanceRecord> = emptyList(), val evidence: List<Checkpoint> = emptyList(),
    val busy: Boolean = false, val message: String? = null, val route: String = "home",
    val rosterClass: ClassInfo? = null, val roster: List<RosterMember> = emptyList(),
    val calibrationClass: ClassInfo? = null, val calibrationBeacons: List<BeaconIdentity> = Defaults.beacons, val connection: String = "",
    val diagnostic: ScanBatch? = null, val calibration: List<CalibrationSample> = emptyList(), val roomConfig: PresenceConfig = Defaults.config
)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repository = PresenceRepository(app)
    private val mutable = MutableStateFlow(AppState(account = repository.account))
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private var foreground = false
    fun foreground(value: Boolean) { foreground = value }
    init {
        if(repository.account != null) {
            SyncWorker.enqueue(app)
            task { refreshInternal() }
        }
        viewModelScope.launch {
            while (isActive) {
                if (foreground && repository.account != null && !mutable.value.busy) runCatching { refreshInternal(false) }
                delay(15_000)
            }
        }
    }
    private fun task(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "Operation failed") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun login(email: String, password: String, role: String) = task {
        val account = repository.login(email, password, role)
        mutable.value = AppState(account = account, busy = true)
        refreshInternal()
        SyncWorker.enqueue(getApplication())
    }
    fun reset(email: String) = task { repository.resetPassword(email); message("Password reset requested. Check your email.") }
    fun setPassword(link: String, password: String) = task {
        repository.setPasswordFromLink(link, password)
        message("Password saved. Sign in using your email and new password.")
    }
    fun logout() {
        getApplication<Application>().stopService(android.content.Intent(getApplication(), MonitoringService::class.java))
        observer?.cancel(); repository.logout(); mutable.value = AppState()
    }
    fun refresh() = task { refreshInternal(); SyncWorker.enqueue(getApplication()) }
    private suspend fun refreshInternal(full: Boolean = true) {
        val who = repository.account ?: return
        val classes = if (full) repository.classes() else mutable.value.classes
        val sessions = if (full) repository.sessions() else repository.refreshSessionStates()
        if (repository.account?.uid != who.uid) return
        mutable.update { it.copy(classes = classes, sessions = sessions, connection = if(repository.usedOfflineCache) "Offline cached sessions. Received attendance may be out of date." else "") }
        mutable.value.selected?.let { selected ->
            val latest = sessions.firstOrNull { it.id == selected.id } ?: selected
            val records = repository.attendance(latest)
            mutable.update { it.copy(selected = latest, attendance = records) }
        }
    }
    fun select(session: ClassSession) {
        observer?.cancel()
        mutable.update { it.copy(selected = session, route = "session", evidence = emptyList(), points = emptyList(), attendance = emptyList()) }
        val uid = repository.account?.uid ?: return
        if (repository.account?.role == "STUDENT") observer = viewModelScope.launch {
            repository.dao.observe(uid, session.id).collect { items -> mutable.update { it.copy(points = items) } }
        }
        task { mutable.update { it.copy(attendance = repository.attendance(session)) } }
    }
    fun route(value: String) { mutable.update { it.copy(route = value, message = null) } }
    fun prepareSession(info: ClassInfo) = task {
        val config = repository.roomConfig(info.roomId)
        mutable.update { it.copy(setupClass = info, roomConfig = config, route = "setup") }
    }
    fun start(info: ClassInfo, timing: SessionTiming) = task {
        val created = repository.startSession(info, timing)
        refreshInternal(); select(created)
        // select runs inside this busy task, so load the newly-created backend records here.
        mutable.update { it.copy(attendance = repository.attendance(created)) }
    }
    fun end() = task {
        val selected = mutable.value.selected ?: return@task
        repository.endSession(selected); refreshInternal()
    }
    fun evidence(uid: String) = task {
        val selected = mutable.value.selected ?: return@task
        mutable.update { it.copy(evidence = repository.evidence(selected, uid)) }
    }
    fun override(uid: String, outcome: String, reason: String) = task {
        val selected = mutable.value.selected ?: return@task
        repository.override(selected, uid, outcome, reason); refreshInternal(); message("Attendance override saved with audit record.")
    }
    fun loadRoster(info: ClassInfo) = task {
        val members = repository.roster(info.id)
        mutable.update { it.copy(rosterClass = info, roster = members, route = "roster") }
    }
    fun enroll(email: String) = task {
        val info = mutable.value.rosterClass ?: return@task
        repository.enroll(info.id, email)
        val members = repository.roster(info.id)
        mutable.update { it.copy(roster = members, message = "Student enrolled for future sessions.") }
    }
    fun removeEnrollment(uid: String) = task {
        val info = mutable.value.rosterClass ?: return@task
        repository.removeEnrollment(info.id, uid)
        val members = repository.roster(info.id)
        mutable.update { it.copy(roster = members, message = "Enrollment removed. Existing session snapshots are preserved.") }
    }
    fun loadCalibration(roomId: String) = task {
        val who = repository.account ?: return@task
        val info = mutable.value.classes.first { it.roomId == roomId }
        val beacons = repository.roomBeacons(roomId)
        mutable.update { it.copy(calibrationClass = info, calibrationBeacons = beacons, roomConfig = repository.roomConfig(roomId), calibration = repository.dao.calibrations(who.uid, roomId), diagnostic = null, route = "calibration") }
    }
    fun collectCalibration(info: ClassInfo, label: String, inside: Boolean) = task {
        check(!MonitoringService.state.value.running) { "Stop attendance monitoring before calibration." }
        check(label.isNotBlank()) { "Enter a physical location label." }
        val scanner = RadioScanner(getApplication()); check(scanner.readiness().isEmpty()) { scanner.readiness().joinToString(" ") }
        val batch = scanner.collect(info.roomId, mutable.value.calibrationBeacons, 25_000)
        val who = repository.account ?: return@task
        val result = PresenceEngine().evaluate(batch, mutable.value.roomConfig, mutable.value.calibrationBeacons)
        check(result.status != PresenceStatus.INSUFFICIENT_DATA) { "Calibration needs fresh readings from at least three beacons on both radios. ${result.reasons.joinToString()}" }
        repository.dao.calibration(CalibrationSample(UUID.randomUUID().toString(), who.uid, info.roomId, label.trim(), inside,
            System.currentTimeMillis(), "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}", repository.json.encodeToString(batch)))
        mutable.update { it.copy(diagnostic = batch, calibration = repository.dao.calibrations(who.uid, info.roomId)) }
    }
    fun publish(roomId: String, wifiInside: Int, bleInside: Int, wifiOutside: Int, bleOutside: Int) = task {
        val config = mutable.value.roomConfig.copy(wifiInsideDbm = wifiInside, bleInsideDbm = bleInside,
            wifiOutsideDbm = wifiOutside, bleOutsideDbm = bleOutside, calibrated = true, version = mutable.value.roomConfig.version + 1)
        repository.publishConfig(roomId, config)
        mutable.update { it.copy(roomConfig = config, message = "Configuration published. Existing sessions keep their original configuration.") }
    }
    fun diagnostics(session: ClassSession) = task {
        check(!MonitoringService.state.value.running) { "Stop monitoring before running a separate diagnostic scan." }
        val scanner = RadioScanner(getApplication()); check(scanner.readiness().isEmpty()) { scanner.readiness().joinToString(" ") }
        val batch = scanner.collect(session.roomId, session.beacons, session.config.bleDurationMs)
        mutable.update { it.copy(diagnostic = batch) }
    }
}
