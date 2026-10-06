package com.classroompresence.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classroompresence.core.*
import com.classroompresence.data.*
import com.classroompresence.scanner.RadioScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PresenceTheme {
                PresenceApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PresenceApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val monitor by MonitoringService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        vm.foreground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_RESUME) permissionRevision++
            if(event == Lifecycle.Event.ON_START) vm.foreground(true)
            if(event == Lifecycle.Event.ON_STOP) vm.foreground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.foreground(false) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++; vm.message("Permissions updated. Check readiness before starting a scan.")
    }
    val readiness = remember(permissionRevision, state.route, state.busy) { RadioScanner(context).readiness() }
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            runCatching { context.contentResolver.openOutputStream(it)?.use { stream -> stream.write(exportText.toByteArray()) } ?: error("Unable to open export") }
                .onSuccess { vm.message("CSV saved.") }.onFailure { vm.message(it.message) }
        }
    }
    fun requestPermissions() = permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    fun startMonitoring() {
        val session = state.selected ?: return
        if (RadioScanner(context).readiness().isNotEmpty()) { requestPermissions(); vm.message("Enable the required permissions and radios, then press Start again."); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(); vm.message("Grant precise location for the foreground monitoring service, then press Start again."); return
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java)
                .putExtra("sessionId", session.id))
        }.onFailure { vm.message(it.message ?: "Unable to start monitoring") }
    }
    val mainRoutes = listOf("home", "classes", "history", "more")
    BackHandler(enabled = state.account != null && state.route != "home") { vm.route("home") }
    val scrollState = key(state.route, state.account?.uid) { rememberScrollState() }
    Scaffold(bottomBar = {
        if (state.account != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            mainRoutes.forEach { route ->
                NavigationBarItem(selected = state.route == route, onClick = { vm.route(route) },
                    icon = { NavigationGlyph(route) }, label = { Text(route.replaceFirstChar(Char::uppercase)) })
            }
        }
    }, topBar = {
        TopAppBar(title = { Column { Text("Presence", fontWeight = FontWeight.Bold)
            Text(state.account?.let { "${it.role.lowercase().replaceFirstChar(Char::uppercase)} workspace" } ?: "Attendance with explainable evidence", style = MaterialTheme.typography.labelMedium) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = { if (state.account != null) TextButton(onClick = vm::logout, enabled = !state.busy) { Text("Sign out") } })
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { text -> Panel { Text(text); TextButton(onClick = { vm.message(null) }) { Text("Dismiss") } } }
            if (state.connection.isNotBlank()) Panel(tint = MaterialTheme.colorScheme.surfaceVariant) {
                Text("Offline view", fontWeight = FontWeight.SemiBold)
                Text(state.connection, style = MaterialTheme.typography.bodySmall)
            }
            if (state.account == null) {
                LoginScreen(state.busy, vm.repository.cloudConfigured, vm::login, vm::reset, vm::setPassword)
            } else {
                if (state.route !in mainRoutes) TextButton(onClick = { vm.route("home") }) { Text("← Dashboard") }
                when (state.route) {
                    "home", "classes", "history", "more" -> Dashboard(state, vm)
                    "setup" -> state.setupClass?.let { info ->
                        SessionSetupContent(info, state.roomConfig, state.busy,
                            { timing -> vm.start(info, timing) }, { vm.loadCalibration(info.roomId) })
                    }
                    "roster" -> RosterScreen(state, vm)
                    "calibration" -> CalibrationScreen(state, vm, readiness, ::requestPermissions) {
                        exportText = calibrationCsv(state.calibration, vm.repository)
                        export.launch("calibration-${System.currentTimeMillis()}.csv")
                    }
                    else -> state.selected?.let { session ->
                        SessionScreen(state, monitor, vm, readiness, ::requestPermissions,
                            { startMonitoring() },
                            { context.stopService(Intent(context, MonitoringService::class.java)) },
                            { permissionRevision++; context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                            { exportText = attendanceCsv(session, state.attendance); export.launch("attendance-${session.id}.csv") })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun Panel(tint: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = tint), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LoginScreen(busy: Boolean, configured: Boolean, login: (String, String, String) -> Unit, reset: (String) -> Unit, setPassword: (String, String) -> Unit) {
    var role by rememberSaveable { mutableStateOf("STUDENT") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }
    var setupLink by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    Panel(tint = MaterialTheme.colorScheme.primaryContainer) {
        LabelBadge("CLASSROOM ATTENDANCE")
        Heading("Your class.\nAll in one place.", "Start a class, follow your attendance, and stay connected.")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(selected = role == "STUDENT", onClick = { role = "STUDENT" }, label = { Text("Student login") })
        FilterChip(selected = role == "TEACHER", onClick = { role = "TEACHER" }, label = { Text("Teacher login") })
    }
    Panel {
        Text("${role.lowercase().replaceFirstChar(Char::uppercase)} sign-in", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(email, { email = it }, label = { Text("Institution email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { login(email, password, role) }, enabled = configured && !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        TextButton(onClick = { reset(email) }, enabled = configured && !busy && email.isNotBlank()) { Text("Forgot password") }
        if (!configured) Text("Your institution connection is not configured. Contact your administrator to enable sign-in.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showSetup = !showSetup }, enabled = configured && !busy) { Text("Set password with administrator link") }
        if (showSetup) {
            Text("Paste your private setup link here without opening it in a browser. Links expire; ask your administrator for a new one if needed.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(setupLink, { setupLink = it }, label = { Text("Private password setup link") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password (12+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { setPassword(setupLink, newPassword); setupLink = ""; newPassword = "" }, enabled = !busy && setupLink.isNotBlank() && newPassword.length >= 12) { Text("Save password") }
        }
        Text("Your administrator assigns roles and enrollments. Choosing a login option does not change your account permissions.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun Dashboard(state: AppState, vm: AppViewModel) {
    val account = state.account!!
    val teacher = account.role == "TEACHER"
    val active = state.sessions.filter { it.state == "ACTIVE" && it.endMs > System.currentTimeMillis() }
    val ended = state.sessions.filterNot { it in active }
    when (state.route) {
        "more" -> {
            Heading("More", "Your account and classroom tools.")
            Panel {
                Text(account.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                LabelBadge(if (teacher) "Teacher account" else "Student account")
                Text("Your institution manages your role and class access.")
                OutlinedButton(onClick = vm::refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh & sync") }
            }

        }
        "history" -> {
            Heading("Session history", if (teacher) "Review completed classes and export attendance." else "View results from your previous classes.")
            if (ended.isEmpty()) EmptyPanel("No completed sessions", "Completed classes will appear here when a session ends.")
            ended.forEach { SessionTile(it, teacher, state.busy) { vm.select(it) } }
        }
        "classes" -> {
            Heading(if (teacher) "Your classes" else "Enrolled classes", if (teacher) "Start a session or manage your classroom." else "Your institution adds you to these classes.")
            if (state.classes.isEmpty()) EmptyPanel("No classes assigned", "Ask your administrator to add you to a class.")
            state.classes.forEach { ClassTile(it, state, vm) }
        }
        else -> {
            Heading("Hello, ${account.name.substringBefore('@')}", if (teacher) "Ready for your next class?" else "Your classroom, at a glance.")
            Panel(tint = MaterialTheme.colorScheme.primaryContainer) {
                Text(if (teacher) "Teaching overview" else "Your learning day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric(active.size.toString(), "Active sessions", Modifier.weight(1f))
                    Metric(state.classes.size.toString(), if (teacher) "Assigned classes" else "Enrolled classes", Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (teacher && active.isEmpty()) "Start a class" else "Happening now", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = vm::refresh, enabled = !state.busy) { Text("Refresh & sync") }
            }
            if (active.isEmpty() && !teacher) EmptyPanel("No active class right now", "When your teacher starts a session, it will appear here. Open it to begin monitoring.")
            active.forEach { SessionTile(it, teacher, state.busy) { vm.select(it) } }
            if (!teacher || active.isNotEmpty()) Text("Your classes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (state.classes.isEmpty()) EmptyPanel("No classes assigned", "Ask your administrator to add you to a class.")
            state.classes.forEach { ClassTile(it, state, vm) }
            TextButton(onClick = { vm.route("history") }, modifier = Modifier.fillMaxWidth()) { Text("View session history") }
        }
    }
}

@Composable private fun EmptyPanel(title: String, detail: String) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ClassTile(info: ClassInfo, state: AppState, vm: AppViewModel) {
    val teacher = state.account?.role == "TEACHER"
    val running = state.sessions.any { it.classId == info.id && it.state == "ACTIVE" && it.endMs > System.currentTimeMillis() }
    Panel {
        LabelBadge("Room ${info.roomId.removePrefix("ROOM_")}")
        Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (teacher) {
            Button(onClick = { vm.prepareSession(info) }, enabled = !state.busy && !running, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Class in progress" else "Set up class") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.loadRoster(info) }, enabled = !state.busy) { Text("Add students") }
                TextButton(onClick = { vm.loadCalibration(info.roomId) }, enabled = !state.busy) { Text("Room calibration") }
            }
        } else Text("${if (running) "A session is active. Open monitoring above to collect attendance." else "Waiting for your teacher to start a session."}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SessionSetupContent(info: ClassInfo, config: PresenceConfig, busy: Boolean,
    start: (SessionTiming) -> Unit, calibration: () -> Unit) {
    var duration by rememberSaveable(info.id) { mutableStateOf("60") }
    var minimum by rememberSaveable(info.id) { mutableStateOf("40") }
    val timing = duration.toIntOrNull()?.let { d -> minimum.toIntOrNull()?.let { SessionTiming(d, it) } }
    val valid = timing != null && runCatching { timing.validate() }.isSuccess
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Set up class", "${info.title} · starts now when you press Start class.")
        Panel {
            LabelBadge(if (config.calibrated) "Room ready" else "Room calibration required", positive = config.calibrated)
            Text(if (config.calibrated) "The saved calibration for ${info.roomId.removePrefix("ROOM_")} is reused automatically. You do not need to calibrate for each class." else "Calibrate this room once before starting its first class.")
            TextButton(onClick = calibration, enabled = !busy) { Text(if (config.calibrated) "View room calibration" else "Calibrate room") }
        }
        Panel {
            Text("Class timing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(duration, { duration = it }, label = { Text("Class duration (minutes)") },
                supportingText = { Text("15–120 minutes, in steps of 5") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("class-duration"))
            OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum presence (minutes)") },
                supportingText = { Text("Between 1 minute and the class duration") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("minimum-presence"))
            if (!valid) Text("Enter a valid duration and minimum presence time.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { timing?.takeIf { valid }?.let(start) }, enabled = valid && config.calibrated && !busy, modifier = Modifier.fillMaxWidth()) { Text("Start class") }
        }
    }
}

@Composable private fun SessionTile(session: ClassSession, teacher: Boolean, busy: Boolean, open: () -> Unit) {
    val active = session.state == "ACTIVE" && session.endMs > System.currentTimeMillis()
    Panel {
        LabelBadge(if (active) "Live session" else "Completed session", positive = active)
        Text(session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Room ${session.roomId.removePrefix("ROOM_")} · ${time(session.startMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = open, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (teacher) "Review session" else if (active) "Open monitoring" else "View attendance") }
    }
}

@Composable private fun ExpandableSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Panel {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (expanded) "−" else "+", modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (expanded) content()
    }
}

@Composable private fun SessionScreen(state: AppState, monitor: MonitorState, vm: AppViewModel, readiness: List<String>,
    requestPermissions: () -> Unit, start: () -> Unit, stop: () -> Unit, settings: () -> Unit, export: () -> Unit) {
    val session = state.selected!!
    val account = state.account!!
    val active = session.state == "ACTIVE" && session.endMs > System.currentTimeMillis()
    var confirmEnd by remember(session.id) { mutableStateOf(false) }
    Heading(session.title, "Room ${session.roomId.removePrefix("ROOM_")} · ${time(session.startMs)}")
    LabelBadge(if (active) "Live session · ends ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.endMs))}" else "Session completed", positive = active)
    if (account.role == "STUDENT") {
        val thisMonitor = monitor.sessionId == session.id
        Panel(tint = MaterialTheme.colorScheme.primaryContainer) {
            Text(if (thisMonitor && monitor.running) "Monitoring in progress" else if (active) "Get ready for class" else "Your session has ended", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (thisMonitor) monitor.message else if (active) "Start monitoring to collect checkpoints for this class." else "Review your saved checkpoints and received result below.")
            monitor.display?.takeIf { thisMonitor }?.let { StatusPill(it) }
            if (active && !monitor.running) {
                if (readiness.isNotEmpty()) {
                    Text("Before you start", fontWeight = FontWeight.SemiBold)
                    readiness.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = requestPermissions, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
                } else LabelBadge("Permissions and radios ready", positive = true)
                Button(onClick = { start() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Start real beacon monitoring") }
            }
            if (monitor.running && thisMonitor) OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Stop monitoring") }
            else if (monitor.running) Text("Another class is being monitored. Open that session to stop it before starting this one.")
            Text("Keep Wi-Fi, Bluetooth and Location on. Starting late or stopping early leaves checkpoints missing.", style = MaterialTheme.typography.bodySmall)
        }
        val points = state.points.map { vm.repository.json.decodeFromString<Checkpoint>(it.json) }
        val summary = AttendanceAggregator.aggregate(points, session.config)
        SummaryCard(AttendanceRecord(account.uid, summary), "Your checkpoint progress")
        if (state.attendance.isEmpty()) EmptyPanel("Waiting for a received result", "Your local progress is provisional. Refresh to check for a backend result.")
        state.attendance.forEach { SummaryCard(it, "Received backend result") }
        ExpandableSection("Saved checkpoints (${state.points.size})") {
            if (state.points.isEmpty()) Text("No checkpoints saved yet. Start monitoring during an active class.")
            state.points.reversed().forEach { item ->
                val point = vm.repository.json.decodeFromString<Checkpoint>(item.json)
                HorizontalDivider()
                Text("Checkpoint ${point.slot + 1} · ${time(point.capturedAtMs)}", fontWeight = FontWeight.SemiBold)
                LabelBadge(item.syncState.replace('_', ' '))
                ResultView(point.result)
                if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        Text("Received attendance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Results update after accepted uploads. Missing evidence does not confirm absence.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.attendance.isEmpty()) EmptyPanel("No attendance received yet", "Students need to start monitoring during this session. Refresh to check for new results.")
        state.attendance.forEach { record ->
            key(session.id, record.uid) {
                SummaryCard(record, record.uid)
                TextButton(onClick = { vm.evidence(record.uid) }, enabled = !state.busy) { Text("Inspect ${record.uid} checkpoints") }
                ExpandableSection("Correct attendance for ${record.uid}") { OverrideEditor(record.uid, state.busy, vm::override) }
            }
        }
        OutlinedButton(onClick = export, enabled = state.attendance.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Export attendance CSV") }
        if (active) OutlinedButton(onClick = { confirmEnd = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("End this class") }
        state.evidence.forEach { point -> Panel { Text("${point.uid} · checkpoint ${point.slot + 1} · ${time(point.capturedAtMs)}"); ResultView(point.result) } }
    }
    OutlinedButton(onClick = vm::refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh & retry uploads") }
    ExpandableSection("Attendance requirements") {
        if (session.config.minInsideSlots > 0) {
            Text("Be present in the classroom for at least ${sampleDurationLabel(session.config.minInsideSlots * session.config.intervalMs)}.")
        } else Text("Meet your teacher's attendance requirement by staying in the classroom during class.")
        Text("Keep monitoring on during class so your attendance can be recorded.", style = MaterialTheme.typography.bodySmall)
    }
    ExpandableSection("Device settings & diagnostics") {
        if (account.role == "STUDENT") {
            OutlinedButton(onClick = settings, modifier = Modifier.fillMaxWidth()) { Text("App / battery settings") }
            Text("Grant precise location. If scans stop while locked, check that battery settings allow background monitoring.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { vm.diagnostics(session) }, enabled = !state.busy && !monitor.running, modifier = Modifier.fillMaxWidth()) { Text("Run real radio diagnostic") }
        state.diagnostic?.let { ResultView(PresenceEngine().evaluate(it, session.config, session.beacons)) }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false },
        title = { Text("End this class?") }, text = { Text("Student monitoring will stop for this session. Saved checkpoints remain available for review.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; vm.end() }, enabled = !state.busy) { Text("End class") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep class active") } })
}

@Composable private fun StatusPill(status: PresenceStatus) {
    val tint = when (status) { PresenceStatus.INSIDE -> Color(0xFFD5EDE3); PresenceStatus.OUTSIDE -> Color(0xFFF5D9D7); else -> Color(0xFFFFE9B5) }
    Surface(color = tint, shape = MaterialTheme.shapes.small) { Text(when (status) { PresenceStatus.INSIDE -> "Inside classroom"; PresenceStatus.OUTSIDE -> "Outside classroom"; PresenceStatus.UNCERTAIN -> "Uncertain reading"; PresenceStatus.INSUFFICIENT_DATA -> "Not enough data" }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color(0xFF202638), fontWeight = FontWeight.Bold) }
}
@Composable private fun ResultView(result: PresenceResult) {
    StatusPill(result.status)
    Text("Wi-Fi: ${result.wifi.detected} detected / ${result.wifi.strong} strong · BLE: ${result.ble.detected} detected / ${result.ble.strong} strong")
    result.wifi.medians.keys.union(result.ble.medians.keys).sorted().forEach { id ->
        Text("${id.substringAfter("CLASSROOM_")}  Wi-Fi ${result.wifi.medians[id] ?: "—"} / BLE ${result.ble.medians[id] ?: "—"} dBm", style = MaterialTheme.typography.bodySmall)
    }
    Text("${result.reasons.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
}
@Composable private fun SummaryCard(record: AttendanceRecord, title: String) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val outcome = record.overrideOutcome ?: record.summary.outcome.name
        LabelBadge(when (outcome) {
            "ELIGIBLE" -> "Attendance eligible"
            "BELOW_THRESHOLD" -> "Below attendance threshold"
            else -> "Not enough valid checkpoints"
        }, positive = outcome == "ELIGIBLE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("${record.summary.valid}/${record.summary.expected}", "Valid checkpoints", Modifier.weight(1f))
            Metric(if (record.summary.valid > 0) "${record.summary.insidePercentage}%" else "—", "Inside among valid", Modifier.weight(1f))
        }
        LinearProgressIndicator(progress = { if (record.summary.expected > 0) (record.summary.valid.toFloat() / record.summary.expected).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
        Text("${record.summary.inside} inside · ${record.summary.outside} outside · ${record.summary.uncertain} uncertain · ${record.summary.missing} missing", style = MaterialTheme.typography.bodySmall)
        Text(if (record.final) "Final backend record" else "Provisional result", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record.updatedAtMs > 0) Text("Updated ${time(record.updatedAtMs)}", style = MaterialTheme.typography.bodySmall)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun OverrideEditor(uid: String, busy: Boolean, save: (String, String, String) -> Unit) {
    var reason by remember(uid) { mutableStateOf("") }
    var outcome by remember(uid) { mutableStateOf("ELIGIBLE") }
    Panel {
        Text("Teacher review", fontWeight = FontWeight.Bold)
        OutlinedTextField(reason, { reason = it }, label = { Text("Reason for correction") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(outcome == "ELIGIBLE", { outcome = "ELIGIBLE" }, label = { Text("Eligible") })
            FilterChip(outcome == "BELOW_THRESHOLD", { outcome = "BELOW_THRESHOLD" }, label = { Text("Below threshold") })
        }
        Button(onClick = { save(uid, outcome, reason) }, enabled = !busy && reason.trim().length >= 10) { Text("Save audited correction") }
    }
}

@Composable private fun CalibrationScreen(state: AppState, vm: AppViewModel, readiness: List<String>, permissions: () -> Unit, export: () -> Unit) {
    val info = state.calibrationClass ?: return
    Heading("Room calibration", "${info.roomId.removePrefix("ROOM_")} · saved for future classes in this room.")
    var editing by remember(info.roomId, state.roomConfig.version) { mutableStateOf(!state.roomConfig.calibrated) }
    if (state.roomConfig.calibrated) Panel(tint = MaterialTheme.colorScheme.primaryContainer) {
        LabelBadge("Saved calibration · v${state.roomConfig.version}", positive = true)
        Text("This room is ready. Every new class uses this saved calibration automatically.")
        Text("Update it only when beacon placement or the room environment changes, or a field evaluation shows the thresholds need adjustment.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { editing = !editing }) { Text(if (editing) "Keep saved calibration" else "Update calibration") }
    }
    if (!editing) return
    var label by rememberSaveable { mutableStateOf("Center") }
    var inside by rememberSaveable { mutableStateOf(true) }
    var wi by remember(state.roomConfig) { mutableStateOf(state.roomConfig.wifiInsideDbm.toString()) }
    var bl by remember(state.roomConfig) { mutableStateOf(state.roomConfig.bleInsideDbm.toString()) }
    var wo by remember(state.roomConfig) { mutableStateOf(state.roomConfig.wifiOutsideDbm.toString()) }
    var bo by remember(state.roomConfig) { mutableStateOf(state.roomConfig.bleOutsideDbm.toString()) }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    Panel {
        if (readiness.isNotEmpty()) { readiness.forEach { Text(it) }; OutlinedButton(onClick = permissions) { Text("Grant scan permissions") } }
        OutlinedTextField(label, { label = it }, label = { Text("Physical location label") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(inside, { inside = true }, label = { Text("Inside") })
            FilterChip(!inside, { inside = false }, label = { Text("Outside") })
        }
        Button(onClick = { vm.collectCalibration(info, label, inside) }, enabled = !state.busy) { Text("Collect 25-second sample") }
        Text("${state.calibration.count { it.inside }} inside / ${state.calibration.count { !it.inside }} outside samples")
        state.diagnostic?.let { ResultView(PresenceEngine().evaluate(it, state.roomConfig, state.calibrationBeacons)) }
    }
    Panel {
        Text("Versioned thresholds", style = MaterialTheme.typography.titleLarge)
        Text("These initial values are experimental. Compare doors and adjacent rooms on separate test recordings before publishing.")
        ThresholdField("Wi-Fi inside ≥", wi) { wi = it }
        ThresholdField("BLE inside ≥", bl) { bl = it }
        ThresholdField("Wi-Fi outside ≤", wo) { wo = it }
        ThresholdField("BLE outside ≤", bo) { bo = it }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(reviewed, { reviewed = it }); Text("I reviewed inside/outside field samples and known ambiguous areas.", modifier = Modifier.weight(1f)) }
        Button(onClick = {
            val values = listOf(wi, bl, wo, bo).map { it.toIntOrNull() }
            if (values.any { it == null }) vm.message("Enter four valid integer thresholds.")
            else vm.publish(info.roomId, values[0]!!, values[1]!!, values[2]!!, values[3]!!)
        }, enabled = !state.busy && reviewed) { Text("Publish new configuration") }
        OutlinedButton(onClick = export, enabled = state.calibration.isNotEmpty()) { Text("Export calibration CSV") }
    }
    state.calibration.take(20).forEach { sample ->
        val batch = vm.repository.json.decodeFromString<ScanBatch>(sample.json)
        Panel {
            Text("${sample.label} · ${if (sample.inside) "inside" else "outside"} · ${time(sample.capturedAtMs)}", fontWeight = FontWeight.Bold)
            Text(sample.device, style = MaterialTheme.typography.bodySmall)
            Text("Wi-Fi ${stats(batch.wifi.observations)}")
            Text("BLE ${stats(batch.ble.observations)}")
        }
    }
}
@Composable private fun ThresholdField(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, update, label = { Text("$label (dBm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
private fun stats(obs: List<Observation>): String {
    if (obs.isEmpty()) return "No observations"
    val values = obs.map { it.rssiDbm }.sorted(); val mean = values.average()
    val deviation = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    return "n=${values.size}, median=${values[values.size/2]}, min=${values.first()}, max=${values.last()}, σ=${"%.1f".format(deviation)} dB"
}
private fun time(ms: Long) = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun csv(value: Any?): String {
    val text = value?.toString().orEmpty()
    // Escape spreadsheet formulas in exported text fields as well as CSV delimiters.
    val safe = if (value is String && text.firstOrNull() in listOf('=', '+', '-', '@')) "'" + text else text
    return "\"" + safe.replace("\"", "\"\"") + "\""
}
private fun attendanceCsv(session: ClassSession, rows: List<AttendanceRecord>) = buildString {
    appendLine("session,uid,outcome,inside,outside,uncertain,valid,expected,inside_percent,final,updated_at")
    rows.forEach { r -> appendLine(listOf(session.id, r.uid, r.overrideOutcome ?: r.summary.outcome.name, r.summary.inside,
        r.summary.outside, r.summary.uncertain, r.summary.valid, r.summary.expected, r.summary.insidePercentage, r.final, r.updatedAtMs).joinToString(",") { csv(it) }) }
}
private fun calibrationCsv(rows: List<CalibrationSample>, repository: PresenceRepository) = buildString {
    appendLine("sample,room,label,inside,device,captured_at,technology,esp,rssi_dbm,elapsed_ms")
    rows.forEach { sample ->
        val batch = repository.json.decodeFromString<ScanBatch>(sample.json)
        listOf("wifi" to batch.wifi, "ble" to batch.ble).forEach { (technology, scan) ->
            scan.observations.forEach { obs -> appendLine(listOf(sample.id, sample.roomId, sample.label, sample.inside, sample.device,
                sample.capturedAtMs, technology, obs.espId, obs.rssiDbm, obs.elapsedMs).joinToString(",") { csv(it) }) }
        }
    }
}

@Composable private fun RosterScreen(state: AppState, vm: AppViewModel) {
    val info = state.rosterClass ?: return
    var email by rememberSaveable { mutableStateOf("") }
    var removal by remember { mutableStateOf<RosterMember?>(null) }
    Heading("Class roster", "${info.title} · enrollment changes apply to future sessions.")
    Panel {
        Text("${state.roster.size} enrolled students", fontWeight = FontWeight.Bold)
        Text("Student accounts must first be created and assigned the student role by your administrator.")
        OutlinedTextField(email, { email = it }, label = { Text("Student email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.enroll(email) }, enabled = !state.busy && email.isNotBlank()) { Text("Enroll student") }
    }
    if (state.roster.isEmpty()) EmptyPanel("No enrolled students", "Add students using their institution email above.")
    state.roster.forEach { member -> Panel {
        Text(member.name.ifBlank { member.uid }, fontWeight = FontWeight.Bold)
        Text(member.email)
        TextButton(onClick = { removal = member }, enabled = !state.busy) { Text("Remove from future sessions") }
    } }
    removal?.let { member -> AlertDialog(onDismissRequest = { removal = null },
        title = { Text("Remove enrollment?") },
        text = { Text("${member.name.ifBlank { member.email }} will be removed from future sessions. Existing attendance stays available.") },
        confirmButton = { TextButton(onClick = { removal = null; vm.removeEnrollment(member.uid) }, enabled = !state.busy) { Text("Remove student") } },
        dismissButton = { TextButton(onClick = { removal = null }) { Text("Keep student") } }) }

}
