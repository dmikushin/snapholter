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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dev.snapecg.holter.bluetooth.DeviceManager
import dev.snapecg.holter.connector.ConnectorService
import dev.snapecg.holter.recording.HolterService
import dev.snapecg.holter.recording.RecordingStore

class MainActivity : AppCompatActivity() {

    enum class UiState {
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
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        requestPermissions()
        requestBatteryOptimizationExclusion()

        // Start and bind connector service
        startService(Intent(this, ConnectorService::class.java))
        bindService(Intent(this, ConnectorService::class.java),
            connectorConnection, BIND_AUTO_CREATE)

        setState(UiState.SCANNING_CONNECTOR)
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
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.GONE

        when (state) {
            UiState.SCANNING_CONNECTOR -> {
                statusIcon.text = "\uD83D\uDD0C" // plug
                spinner.visibility = View.VISIBLE
                statusTitle.text = "Searching for connector..."
                statusMessage.text = "Start snapecg-connector on your PC\nand make sure you are on the same network."
                primaryButton.text = "Cancel"
                primaryButton.setOnClickListener { finish() }
            }
            UiState.SCANNING_DEVICE -> {
                statusIcon.text = "\uD83D\uDCF6" // signal
                spinner.visibility = View.VISIBLE
                statusTitle.text = "Searching for SnapECG B10..."
                statusMessage.text = "Turn on the SnapECG B10 device\nand bring it close to the phone."
                primaryButton.text = "Cancel"
                primaryButton.setOnClickListener {
                    disconnectDevice()
                    setState(UiState.SCANNING_CONNECTOR)
                }
            }
            UiState.DEVICE_READY -> {
                statusIcon.text = "\u2705" // checkmark
                statusTitle.text = "Device connected"
                statusMessage.text = "Wear the device comfortably,\nattach electrodes, and press Start."
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
            }
            UiState.COMPLETED -> {
                statusIcon.text = "\u2714\uFE0F" // done
                statusTitle.text = "Recording complete"
                val mins = finalDuration / 60
                val secs = finalDuration % 60
                statusMessage.text = "${finalSamples} samples, ${mins}m ${secs}s"
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
        startForegroundService(intent)
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
        holterService?.let { svc ->
            finalSamples = svc.sampleCount
            finalDuration = if (svc.startTime > 0)
                (System.currentTimeMillis() - svc.startTime) / 1000 else 0
        }
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_STOP
        }
        startService(intent)
        setState(UiState.COMPLETED)
    }

    private fun onShare() {
        val store = RecordingStore(this)
        val xml = store.exportToXml(-1)
        val file = java.io.File(cacheDir, "holter_recording.xml")
        file.writeText(xml)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/xml"
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
            UiState.SCANNING_CONNECTOR -> {
                if (connectorService?.isConnectorConnected == true) {
                    connectDevice()
                    setState(UiState.SCANNING_DEVICE)
                }
            }
            UiState.SCANNING_DEVICE -> {
                if (connectorService?.isConnectorConnected != true) {
                    disconnectDevice()
                    setState(UiState.SCANNING_CONNECTOR)
                    return
                }
                if (holterService?.btState == DeviceManager.State.CONNECTED) {
                    setState(UiState.DEVICE_READY)
                }
            }
            UiState.DEVICE_READY -> {
                if (connectorService?.isConnectorConnected != true) {
                    disconnectDevice()
                    setState(UiState.SCANNING_CONNECTOR)
                    return
                }
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
