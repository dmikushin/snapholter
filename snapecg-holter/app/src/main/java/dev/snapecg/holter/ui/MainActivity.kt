package dev.snapecg.holter.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.snapecg.holter.bluetooth.DeviceManager
import dev.snapecg.holter.bluetooth.DeviceResolver
import dev.snapecg.holter.connector.ConnectorService
import dev.snapecg.holter.recording.HolterService
import dev.snapecg.holter.recording.RecordingStore
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Compose-based UI for the SnapECG Holter app.
 *
 * The activity owns service bindings and an [AppController] that tracks the
 * current [UiState], the most recent COMPLETED-screen stats, and exposes the
 * services' StateFlows so the composable hierarchy can collect them via
 * [collectAsStateWithLifecycle]. UI navigation is reactive: btState changes
 * push us between SCANNING_DEVICE and DEVICE_READY automatically; connector
 * presence flips us out of SCANNING_CONNECTOR.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    enum class UiState {
        HOME,
        SCANNING_CONNECTOR,
        SCANNING_DEVICE,
        DEVICE_READY,
        RECORDING,
        COMPLETED,
    }

    /** Holds completed-recording stats so the COMPLETED screen survives
     *  config changes via rememberSaveable equivalents. */
    data class CompletedSession(
        val samples: Long = 0L,
        val durationSec: Long = 0L,
        val savedFileName: String? = null,
    )

    private val btAddress: String by lazy { DeviceResolver.resolve(this) }

    // --- Service handles + their state flows ---

    private var holterService: HolterService? = null
    private var connectorService: ConnectorService? = null
    private var holterBound = false
    private var connectorBound = false

    private val _holterStats = MutableStateFlow(HolterService.LiveStats(
        isRecording = false, sampleCount = 0L, startTime = 0L, lastHr = 0,
        battery = -1, leadOff = false,
        btState = DeviceManager.State.DISCONNECTED, firmware = "",
    ))
    private val holterStatsFlow: StateFlow<HolterService.LiveStats> = _holterStats

    private val _connectorState = MutableStateFlow(
        ConnectorService.ConnectorUiState(connected = false, address = null)
    )
    private val connectorStateFlow: StateFlow<ConnectorService.ConnectorUiState> = _connectorState

    // --- Activity lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        requestBatteryOptimizationExclusion()

        setContent {
            MaterialTheme {
                HolterRoot(
                    holterStatsFlow = holterStatsFlow,
                    connectorStateFlow = connectorStateFlow,
                    onConnect = ::connectDevice,
                    onDisconnect = ::disconnectDevice,
                    onStartRecording = ::startRecordingFlow,
                    onStopRecording = ::stopRecordingFlow,
                    onAddNote = ::addNote,
                    onShare = ::shareLastRecording,
                    onOpenRecordings = ::openRecordingsFolder,
                )
            }
        }
    }

    override fun onDestroy() {
        if (holterBound) unbindService(holterConnection)
        if (connectorBound) unbindService(connectorConnection)
        super.onDestroy()
    }

    // --- Actions invoked by the composable hierarchy ---

    private fun connectDevice() {
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_CONNECT
            putExtra(HolterService.EXTRA_ADDRESS, btAddress)
        }
        startService(intent)
        if (!holterBound) {
            bindService(Intent(this, HolterService::class.java),
                holterConnection, BIND_AUTO_CREATE)
        }
    }

    private fun disconnectDevice() {
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    /** Returns true if the recording started. False means "deferred — we
     *  need the connector first". */
    private fun startRecordingFlow(useAi: Boolean): Boolean {
        if (useAi && connectorService?.isConnectorConnected != true) {
            startService(Intent(this, ConnectorService::class.java))
            if (!connectorBound) {
                bindService(Intent(this, ConnectorService::class.java),
                    connectorConnection, BIND_AUTO_CREATE)
            }
            return false
        }
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_START
            putExtra(HolterService.EXTRA_ADDRESS, btAddress)
        }
        startForegroundService(intent)
        if (!holterBound) {
            bindService(Intent(this, HolterService::class.java),
                holterConnection, BIND_AUTO_CREATE)
        }
        return true
    }

    /** Stops the recording synchronously (so closeSession runs before the
     *  export thread reads sample_count) and runs export off the UI thread.
     *  Calls back with the saved filename or null on failure. */
    private fun stopRecordingFlow(
        patientName: String,
        onComplete: (samples: Long, durationSec: Long, savedFileName: String?) -> Unit,
    ) {
        val svc = holterService
        if (svc == null) {
            Log.w(TAG, "Stop pressed but HolterService not bound — aborting export")
            Toast.makeText(this, "Service not ready, try again", Toast.LENGTH_SHORT).show()
            return
        }
        val finalSamples = svc.sampleCount
        val finalDuration = if (svc.startTime > 0)
            (System.currentTimeMillis() - svc.startTime) / 1000 else 0
        svc.stopRecording()
        Thread {
            val store = RecordingStore(this)
            val fileName = store.exportToEdf(this, -1, patientName)
            runOnUiThread { onComplete(finalSamples, finalDuration, fileName) }
        }.start()
    }

    private fun addNote(text: String) {
        if (text.isBlank()) return
        holterService?.addEvent(text.trim())
        Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
    }

    private fun openRecordingsFolder() {
        val attempts = listOf(
            Intent.makeMainActivity(ComponentName(
                "com.android.documentsui",
                "com.android.documentsui.files.FilesActivity",
            )).apply {
                data = Uri.parse("content://com.android.externalstorage.documents/root/primary")
            },
            Intent("com.android.fileexplorer.action.DIR_SEL").apply {
                putExtra("path", "/storage/emulated/0/Documents/SnapECG")
            },
            Intent(Intent.ACTION_VIEW).apply {
                // setData + setType clear each other; setDataAndType keeps both.
                setDataAndType(
                    Uri.parse("file:///storage/emulated/0/Documents/SnapECG/"),
                    "resource/folder",
                )
            },
        )
        for (intent in attempts) {
            try { startActivity(intent); return } catch (_: Exception) {}
        }
        Toast.makeText(this,
            "Open Files app and navigate to Documents/SnapECG/",
            Toast.LENGTH_LONG).show()
    }

    private fun shareLastRecording(fileName: String?) {
        if (fileName == null) {
            Toast.makeText(this, "No recording to share", Toast.LENGTH_SHORT).show()
            return
        }
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val cursor = contentResolver.query(
            collection,
            arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(fileName), null,
        )
        val uri = cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                android.content.ContentUris.withAppendedId(collection, id)
            } else null
        }
        if (uri == null) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Recording"))
    }

    // --- Service binding bridges ---

    private val holterConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as HolterService.LocalBinder).getService()
            holterService = svc
            connectorService?.holterService = svc
            holterBound = true
            // Forward the service's StateFlow into ours so composables can
            // collect a single source even if the service unbinds.
            lifecycleScope.launch { svc.liveStats.collect { _holterStats.value = it } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            holterService = null
            holterBound = false
        }
    }

    private val connectorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as ConnectorService.LocalBinder).getService()
            connectorService = svc
            svc.holterService = holterService
            connectorBound = true
            lifecycleScope.launch { svc.connectorState.collect { _connectorState.value = it } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            connectorService = null
            connectorBound = false
        }
    }

    // --- Permissions ---

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    /**
     * Ask the user to exempt SnapECG from battery optimization.
     *
     * Lint flags REQUEST_IGNORE_BATTERY_OPTIMIZATIONS as a Play Store policy
     * concern, but the policy explicitly carves out apps whose primary
     * function requires uninterrupted background execution — continuous
     * Holter ECG capture over 24 hours is exactly that case (a missed BT
     * read = a gap in cardiac data, not a missed notification). We ask
     * once, persist a flag in SharedPreferences, and never re-prompt.
     */
    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("snapecg", MODE_PRIVATE)
        if (prefs.getBoolean("battery_optimization_asked", false)) return
        prefs.edit().putBoolean("battery_optimization_asked", true).apply()
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization request not available: ${e.message}")
        }
    }
}

// =========================================================================
// Composable hierarchy
// =========================================================================

@Composable
fun HolterRoot(
    holterStatsFlow: StateFlow<HolterService.LiveStats>,
    connectorStateFlow: StateFlow<ConnectorService.ConnectorUiState>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartRecording: (useAi: Boolean) -> Boolean,
    onStopRecording: (patientName: String,
                      onComplete: (Long, Long, String?) -> Unit) -> Unit,
    onAddNote: (String) -> Unit,
    onShare: (String?) -> Unit,
    onOpenRecordings: () -> Unit,
) {
    val stats by holterStatsFlow.collectAsStateWithLifecycle()
    val connector by connectorStateFlow.collectAsStateWithLifecycle()

    var uiState by rememberSaveable { mutableStateOf(MainActivity.UiState.HOME) }
    var patientName by rememberSaveable { mutableStateOf("") }
    var useAi by rememberSaveable { mutableStateOf(false) }
    var completedSamples by rememberSaveable { mutableStateOf(0L) }
    var completedDuration by rememberSaveable { mutableStateOf(0L) }
    var completedFile by rememberSaveable { mutableStateOf<String?>(null) }

    // Reactive auto-transitions driven by service flows.
    LaunchedEffect(stats.btState, uiState) {
        when (uiState) {
            MainActivity.UiState.SCANNING_DEVICE ->
                if (stats.btState == DeviceManager.State.CONNECTED)
                    uiState = MainActivity.UiState.DEVICE_READY
            MainActivity.UiState.DEVICE_READY ->
                if (stats.btState != DeviceManager.State.CONNECTED)
                    uiState = MainActivity.UiState.SCANNING_DEVICE
            else -> Unit
        }
    }
    LaunchedEffect(connector.connected, uiState) {
        if (uiState == MainActivity.UiState.SCANNING_CONNECTOR && connector.connected) {
            // Connector arrived; resume the recording-start path.
            if (onStartRecording(useAi)) {
                uiState = MainActivity.UiState.RECORDING
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            MainActivity.UiState.HOME ->
                HomeScreen(
                    onStart = { onConnect(); uiState = MainActivity.UiState.SCANNING_DEVICE },
                    onOpenRecordings = onOpenRecordings,
                )
            MainActivity.UiState.SCANNING_CONNECTOR ->
                ScanningConnectorScreen(
                    onSkip = {
                        useAi = false
                        if (onStartRecording(false)) uiState = MainActivity.UiState.RECORDING
                    },
                )
            MainActivity.UiState.SCANNING_DEVICE ->
                ScanningDeviceScreen(onCancel = {
                    onDisconnect(); uiState = MainActivity.UiState.HOME
                })
            MainActivity.UiState.DEVICE_READY ->
                DeviceReadyScreen(
                    patientName = patientName,
                    onPatientNameChange = { patientName = it },
                    useAi = useAi,
                    onUseAiChange = { useAi = it },
                    onStart = {
                        if (useAi && !connector.connected) {
                            uiState = MainActivity.UiState.SCANNING_CONNECTOR
                        } else if (onStartRecording(useAi)) {
                            uiState = MainActivity.UiState.RECORDING
                        }
                    },
                    onBack = {
                        onDisconnect(); uiState = MainActivity.UiState.SCANNING_DEVICE
                    },
                )
            MainActivity.UiState.RECORDING ->
                RecordingScreen(
                    stats = stats,
                    onStop = {
                        onStopRecording(patientName.trim()) { samples, duration, file ->
                            completedSamples = samples
                            completedDuration = duration
                            completedFile = file
                            uiState = MainActivity.UiState.COMPLETED
                        }
                    },
                    onAddNote = onAddNote,
                )
            MainActivity.UiState.COMPLETED ->
                CompletedScreen(
                    samples = completedSamples,
                    durationSec = completedDuration,
                    savedFileName = completedFile,
                    onShare = { onShare(completedFile) },
                    onNew = {
                        onConnect()
                        uiState = MainActivity.UiState.SCANNING_DEVICE
                    },
                )
        }
    }
}

@Composable
private fun ScreenScaffold(
    icon: String,
    title: String,
    message: String? = null,
    showSpinner: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 64.sp, textAlign = TextAlign.Center)
        if (showSpinner) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium,
             textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 16.sp, textAlign = TextAlign.Center,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun HomeScreen(onStart: () -> Unit, onOpenRecordings: () -> Unit) {
    ScreenScaffold(
        icon = "\u2764\uFE0F",
        title = "SnapECG Holter",
        message = "Portable ECG monitoring",
    ) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start Holter Session", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Recordings", fontSize = 16.sp)
        }
    }
}

@Composable
private fun ScanningConnectorScreen(onSkip: () -> Unit) {
    ScreenScaffold(
        icon = "\uD83D\uDD0C",
        title = "Searching for AI assistant...",
        message = "Start snapecg-connector on your PC\nand make sure you are on the same network.",
        showSpinner = true,
    ) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip", fontSize = 18.sp)
        }
    }
}

@Composable
private fun ScanningDeviceScreen(onCancel: () -> Unit) {
    ScreenScaffold(
        icon = "\uD83D\uDCF6",
        title = "Searching for SnapECG B10...",
        message = "Turn on the SnapECG B10 device\nand bring it close to the phone.",
        showSpinner = true,
    ) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel", fontSize = 18.sp)
        }
    }
}

@Composable
private fun DeviceReadyScreen(
    patientName: String,
    onPatientNameChange: (String) -> Unit,
    useAi: Boolean,
    onUseAiChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        icon = "\u2705",
        title = "Device connected",
        message = "Wear the device comfortably,\nattach electrodes, and press Start.",
    ) {
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = patientName,
            onValueChange = onPatientNameChange,
            label = { Text("Patient name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useAi, onCheckedChange = onUseAiChange)
            Text("Verify initial data with AI agent",
                 modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start Recording", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back", fontSize = 16.sp)
        }
    }
}

@Composable
private fun RecordingScreen(
    stats: HolterService.LiveStats,
    onStop: () -> Unit,
    onAddNote: (String) -> Unit,
) {
    var noteDialogOpen by remember { mutableStateOf(false) }
    if (noteDialogOpen) {
        AddNoteDialog(
            elapsed = formatElapsed(stats.startTime),
            onDismiss = { noteDialogOpen = false },
            onSubmit = { text -> noteDialogOpen = false; onAddNote(text) },
        )
    }
    ScreenScaffold(
        icon = "\u23FA",
        title = "Recording...",
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (stats.lastHr > 0) "${stats.lastHr} bpm" else "-- bpm",
            fontSize = 48.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatElapsed(stats.startTime),
            fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("Battery: ")
                append(if (stats.battery >= 0) "${stats.battery}/3" else "--")
                if (stats.leadOff) append("  \u26A0 LEAD OFF")
            },
            fontSize = 16.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${stats.sampleCount} samples",
            fontSize = 14.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (stats.btState) {
                DeviceManager.State.CONNECTED -> "BT: Connected"
                DeviceManager.State.RECONNECTING -> "\u26A0 BT: Reconnecting..."
                DeviceManager.State.CONNECTING -> "BT: Connecting..."
                DeviceManager.State.DISCONNECTED -> "BT: Disconnected"
            },
            fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop Recording", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { noteDialogOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add Note", fontSize = 16.sp)
        }
    }
}

@Composable
private fun CompletedScreen(
    samples: Long,
    durationSec: Long,
    savedFileName: String?,
    onShare: () -> Unit,
    onNew: () -> Unit,
) {
    val mins = durationSec / 60
    val secs = durationSec % 60
    val msg = buildString {
        append("$samples samples, ${mins}m ${secs}s")
        if (savedFileName != null) {
            append("\nSaved: $savedFileName")
        }
    }
    ScreenScaffold(
        icon = "\u2714\uFE0F",
        title = "Recording complete",
        message = msg,
    ) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text("Share Recording", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Text("New Recording", fontSize = 16.sp)
        }
    }
}

@Composable
private fun AddNoteDialog(
    elapsed: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note at $elapsed") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note (e.g. 'steep climb starting')") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatElapsed(startTime: Long): String {
    val elapsed = if (startTime > 0)
        (System.currentTimeMillis() - startTime) / 1000 else 0L
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
}
