package dev.snapecg.holter.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dev.snapecg.holter.bluetooth.DeviceManager
import dev.snapecg.holter.connector.ConnectorService
import dev.snapecg.holter.recording.HolterService
import dev.snapecg.holter.recording.RecordingStore

class MainActivity : AppCompatActivity() {

    enum class UiState {
        HOME,
        SCANNING_CONNECTOR,
        SCANNING_DEVICE,
        DEVICE_READY,
        RECORDING,
        COMPLETED
    }

    companion object {
        private const val BT_ADDRESS = "34:81:F4:1C:3F:C1"
    }

    private var state = UiState.SCANNING_CONNECTOR
    private var holterService: HolterService? = null
    private var connectorService: ConnectorService? = null
    private var holterBound = false
    private var connectorBound = false
    private val handler = Handler(Looper.getMainLooper())

    // Saved stats for COMPLETED screen
    private var finalSamples = 0L
    private var finalDuration = 0L
    private var savedFileName: String? = null

    // --- UI elements ---
    private lateinit var spinner: ProgressBar
    private lateinit var statusIcon: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView

    private lateinit var statsContainer: LinearLayout
    private lateinit var hrText: TextView
    private lateinit var batteryText: TextView
    private lateinit var durationText: TextView
    private lateinit var samplesText: TextView
    private lateinit var btStateText: TextView

    private lateinit var aiCheckbox: CheckBox
    private lateinit var patientNameInput: EditText
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        requestPermissions()
        requestBatteryOptimizationExclusion()

        setState(UiState.HOME)
        startPolling()
    }

    // --- Layout ---

    private fun buildLayout() {
        val dp = { px: Int -> TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, px.toFloat(), resources.displayMetrics).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(48), dp(32), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        statusIcon = TextView(this).apply {
            textSize = 64f
            gravity = Gravity.CENTER
        }
        root.addView(statusIcon)

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(spinner, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })

        statusTitle = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
        }
        root.addView(statusTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        statusMessage = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF666666.toInt())
        }
        root.addView(statusMessage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // Stats container (RECORDING only)
        statsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(24), 0, 0)
        }

        hrText = TextView(this).apply { textSize = 48f; gravity = Gravity.CENTER }
        statsContainer.addView(hrText)

        durationText = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        statsContainer.addView(durationText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        batteryText = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        statsContainer.addView(batteryText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        samplesText = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF999999.toInt()) }
        statsContainer.addView(samplesText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        btStateText = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        statsContainer.addView(btStateText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        root.addView(statsContainer)

        // Patient name input (DEVICE_READY only)
        patientNameInput = EditText(this).apply {
            hint = "Patient name (optional)"
            textSize = 16f
            visibility = View.GONE
            isSingleLine = true
        }
        root.addView(patientNameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        // AI checkbox (DEVICE_READY only)
        aiCheckbox = CheckBox(this).apply {
            text = "Verify initial data with AI agent"
            isChecked = false
            visibility = View.GONE
        }
        root.addView(aiCheckbox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        // Spacer
        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Buttons
        primaryButton = Button(this).apply {
            textSize = 18f
            isAllCaps = false
        }
        root.addView(primaryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        secondaryButton = Button(this).apply {
            textSize = 16f
            isAllCaps = false
            setBackgroundColor(0x00000000)
        }
        root.addView(secondaryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        setContentView(root)
    }

    // --- State machine ---

    private fun setState(newState: UiState) {
        state = newState
        updateUi()
    }

    private fun updateUi() {
        spinner.visibility = View.GONE
        statsContainer.visibility = View.GONE
        aiCheckbox.visibility = View.GONE
        patientNameInput.visibility = View.GONE
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.GONE

        when (state) {
            UiState.HOME -> {
                statusIcon.text = "\u2764\uFE0F" // heart
                statusTitle.text = "SnapECG Holter"
                statusMessage.text = "Portable ECG monitoring"
                primaryButton.text = "Start Holter Session"
                primaryButton.setOnClickListener {
                    connectDevice()
                    setState(UiState.SCANNING_DEVICE)
                }
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.text = "Open Recordings"
                secondaryButton.setOnClickListener { openRecordingsFolder() }
            }
            UiState.SCANNING_CONNECTOR -> {
                statusIcon.text = "\uD83D\uDD0C" // plug
                spinner.visibility = View.VISIBLE
                statusTitle.text = "Searching for AI assistant..."
                statusMessage.text = "Start snapecg-connector on your PC\nand make sure you are on the same network."
                primaryButton.text = "Skip"
                primaryButton.setOnClickListener {
                    aiCheckbox.isChecked = false
                    onStartRecording()
                }
            }
            UiState.SCANNING_DEVICE -> {
                statusIcon.text = "\uD83D\uDCF6" // signal
                spinner.visibility = View.VISIBLE
                statusTitle.text = "Searching for SnapECG B10..."
                statusMessage.text = "Turn on the SnapECG B10 device\nand bring it close to the phone."
                primaryButton.text = "Cancel"
                primaryButton.setOnClickListener {
                    disconnectDevice()
                    setState(UiState.HOME)
                }
            }
            UiState.DEVICE_READY -> {
                statusIcon.text = "\u2705" // checkmark
                statusTitle.text = "Device connected"
                statusMessage.text = "Wear the device comfortably,\nattach electrodes, and press Start."
                patientNameInput.visibility = View.VISIBLE
                aiCheckbox.visibility = View.VISIBLE
                primaryButton.text = "Start Recording"
                primaryButton.setOnClickListener { onStartRecording() }
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.text = "Back"
                secondaryButton.setOnClickListener {
                    disconnectDevice()
                    setState(UiState.SCANNING_DEVICE)
                }
            }
            UiState.RECORDING -> {
                statusIcon.text = "\u23FA" // record
                statsContainer.visibility = View.VISIBLE
                statusTitle.text = "Recording..."
                statusMessage.text = ""
                primaryButton.text = "Stop Recording"
                primaryButton.setOnClickListener { onStopRecording() }
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.text = "Add Note"
                secondaryButton.setOnClickListener { onAddNote() }
            }
            UiState.COMPLETED -> {
                statusIcon.text = "\u2714\uFE0F" // done
                statusTitle.text = "Recording complete"
                val mins = finalDuration / 60
                val secs = finalDuration % 60
                val fileInfo = if (savedFileName != null) "\nSaved: $savedFileName" else ""
                statusMessage.text = "${finalSamples} samples, ${mins}m ${secs}s$fileInfo"
                primaryButton.text = "Share Recording"
                primaryButton.setOnClickListener { onShare() }
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.text = "New Recording"
                secondaryButton.setOnClickListener {
                    connectDevice()
                    setState(UiState.SCANNING_DEVICE)
                }
            }
        }
    }

    // --- Actions ---

    private fun connectDevice() {
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_CONNECT
            putExtra(HolterService.EXTRA_ADDRESS, BT_ADDRESS)
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

    private fun onStartRecording() {
        // If AI assistance requested, ensure connector is available
        if (aiCheckbox.isChecked && connectorService?.isConnectorConnected != true) {
            // Start connector service and search
            startService(Intent(this, ConnectorService::class.java))
            bindService(Intent(this, ConnectorService::class.java),
                connectorConnection, BIND_AUTO_CREATE)
            setState(UiState.SCANNING_CONNECTOR)
            return
        }

        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_START
            putExtra(HolterService.EXTRA_ADDRESS, BT_ADDRESS)
        }
        startForegroundService(intent)
        if (!holterBound) {
            bindService(Intent(this, HolterService::class.java),
                holterConnection, BIND_AUTO_CREATE)
        }
        setState(UiState.RECORDING)
    }

    private fun onStopRecording() {
        val svc = holterService
        if (svc == null) {
            Log.w("MainActivity", "Stop pressed but HolterService not bound — aborting export")
            Toast.makeText(this, "Service not ready, try again", Toast.LENGTH_SHORT).show()
            return
        }

        finalSamples = svc.sampleCount
        finalDuration = if (svc.startTime > 0)
            (System.currentTimeMillis() - svc.startTime) / 1000 else 0

        // Call stopRecording on the bound service so closeSession() runs
        // synchronously before we kick off the export thread. (BT teardown
        // still happens async inside stopRecording, but that doesn't race
        // with the export — only sample_count in the DB matters here.)
        svc.stopRecording()

        // Export to EDF in background
        val patientName = patientNameInput.text.toString().trim()
        Thread {
            val store = RecordingStore(this)
            val fileName = store.exportToEdf(this, -1, patientName)
            runOnUiThread {
                savedFileName = fileName
                setState(UiState.COMPLETED)
            }
        }.start()
    }

    private fun onAddNote() {
        val input = EditText(this).apply {
            hint = "Note (e.g. 'steep climb starting')"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Add note at ${formatElapsed()}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    holterService?.addEvent(text)
                    Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatElapsed(): String {
        val svc = holterService ?: return "--:--"
        val elapsed = if (svc.startTime > 0)
            (System.currentTimeMillis() - svc.startTime) / 1000 else 0
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        return String.format("%d:%02d:%02d", h, m, s)
    }

    private fun openRecordingsFolder() {
        // Launch the standard Android file manager pointing at Documents
        val uri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        val attempts = listOf(
            // Standard Android Files app
            Intent.makeMainActivity(ComponentName(
                "com.android.documentsui",
                "com.android.documentsui.files.FilesActivity"
            )).apply { data = uri },
            // Xiaomi file manager
            Intent("com.android.fileexplorer.action.DIR_SEL").apply {
                putExtra("path", "/storage/emulated/0/Documents/SnapECG")
            },
            // Generic fallback
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("file:///storage/emulated/0/Documents/SnapECG/")
                type = "resource/folder"
            },
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this,
            "Open Files app and navigate to Documents/SnapECG/",
            Toast.LENGTH_LONG).show()
    }

    private fun onShare() {
        val fileName = savedFileName
        if (fileName == null) {
            Toast.makeText(this, "No recording to share", Toast.LENGTH_SHORT).show()
            return
        }
        // Find the file in MediaStore
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val cursor = contentResolver.query(
            collection,
            arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(fileName), null
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

    // --- Polling & auto-transitions ---

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                pollState()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun pollState() {
        when (state) {
            UiState.HOME -> { /* static */ }
            UiState.SCANNING_CONNECTOR -> {
                // Waiting for connector (AI assistance requested)
                if (connectorService?.isConnectorConnected == true) {
                    onStartRecording() // now connector is ready, proceed
                }
            }
            UiState.SCANNING_DEVICE -> {
                if (holterService?.btState == DeviceManager.State.CONNECTED) {
                    setState(UiState.DEVICE_READY)
                }
            }
            UiState.DEVICE_READY -> {
                if (holterService?.btState != DeviceManager.State.CONNECTED) {
                    setState(UiState.SCANNING_DEVICE)
                }
            }
            UiState.RECORDING -> {
                updateRecordingStats()
            }
            UiState.COMPLETED -> { /* static */ }
        }
    }

    private fun updateRecordingStats() {
        val svc = holterService ?: return
        hrText.text = if (svc.lastHr > 0) "${svc.lastHr} bpm" else "-- bpm"
        batteryText.text = "Battery: ${if (svc.battery >= 0) "${svc.battery}/3" else "--"}" +
                if (svc.leadOff) "  \u26A0 LEAD OFF" else ""

        val elapsed = if (svc.startTime > 0)
            (System.currentTimeMillis() - svc.startTime) / 1000 else 0
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        durationText.text = String.format("%d:%02d:%02d", h, m, s)
        samplesText.text = "${svc.sampleCount} samples"
        btStateText.text = when (svc.btState) {
            DeviceManager.State.CONNECTED -> "BT: Connected"
            DeviceManager.State.RECONNECTING -> "\u26A0 BT: Reconnecting..."
            DeviceManager.State.CONNECTING -> "BT: Connecting..."
            DeviceManager.State.DISCONNECTED -> "BT: Disconnected"
        }
    }

    // --- Service binding ---

    private val holterConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            holterService = (service as HolterService.LocalBinder).getService()
            connectorService?.holterService = holterService
            holterBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            holterService = null
            holterBound = false
        }
    }

    private val connectorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectorService = (service as ConnectorService.LocalBinder).getService()
            connectorService?.holterService = holterService
            connectorBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            connectorService = null
            connectorBound = false
        }
    }

    // --- Permissions ---

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (holterBound) unbindService(holterConnection)
        if (connectorBound) unbindService(connectorConnection)
        super.onDestroy()
    }
}
