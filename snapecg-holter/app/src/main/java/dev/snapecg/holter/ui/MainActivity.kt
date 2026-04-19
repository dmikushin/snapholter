package dev.snapecg.holter.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dev.snapecg.holter.connector.ConnectorService
import dev.snapecg.holter.recording.HolterService
import dev.snapecg.holter.recording.RecordingStore
import android.view.View
import android.widget.*

/**
 * Main activity with three states:
 * 1. Setup: scan for device, connect, verify signal
 * 2. Recording: live stats, event diary button
 * 3. Complete: share recording
 */
class MainActivity : AppCompatActivity() {

    private var holterService: HolterService? = null
    private var connectorService: ConnectorService? = null
    private var bound = false
    private val updateHandler = Handler(Looper.getMainLooper())

    // UI elements (simple programmatic layout for now)
    private lateinit var statusText: TextView
    private lateinit var hrText: TextView
    private lateinit var batteryText: TextView
    private lateinit var durationText: TextView
    private lateinit var connectBtn: Button
    private lateinit var startStopBtn: Button
    private lateinit var eventBtn: Button
    private lateinit var shareBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        requestPermissions()
        requestBatteryOptimizationExclusion()

        // Start connector service
        startService(Intent(this, ConnectorService::class.java))
        bindService(Intent(this, ConnectorService::class.java),
            connectorConnection, BIND_AUTO_CREATE)

        connectBtn.setOnClickListener { onConnect() }
        startStopBtn.setOnClickListener { onStartStop() }
        eventBtn.setOnClickListener { onAddEvent() }
        shareBtn.setOnClickListener { onShare() }
    }

    private fun buildLayout() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "SnapECG Holter"
            textSize = 24f
        }
        layout.addView(statusText)

        hrText = TextView(this).apply { text = "HR: --"; textSize = 48f }
        layout.addView(hrText)

        durationText = TextView(this).apply { text = "Duration: --"; textSize = 18f }
        layout.addView(durationText)

        batteryText = TextView(this).apply { text = "Battery: --"; textSize = 18f }
        layout.addView(batteryText)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        connectBtn = Button(this).apply { text = "Connect" }
        btnRow.addView(connectBtn)

        startStopBtn = Button(this).apply { text = "Start Recording"; isEnabled = false }
        btnRow.addView(startStopBtn)

        layout.addView(btnRow)

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        eventBtn = Button(this).apply { text = "📝 Add Event"; isEnabled = false }
        btnRow2.addView(eventBtn)

        shareBtn = Button(this).apply { text = "📤 Share"; isEnabled = false }
        btnRow2.addView(shareBtn)

        layout.addView(btnRow2)

        setContentView(layout)
    }

    // --- Actions ---

    private fun onConnect() {
        // For MVP: hardcoded address. Production: scan + select.
        val address = "34:81:F4:1C:3F:C1"
        statusText.text = "Connecting to $address..."

        // Bind to HolterService
        val intent = Intent(this, HolterService::class.java)
        startForegroundService(intent)
        bindService(intent, holterConnection, BIND_AUTO_CREATE)

        // Connect via service
        Handler(Looper.getMainLooper()).postDelayed({
            holterService?.let { svc ->
                // Connection happens when START action is sent
                statusText.text = "Connected. Ready to record."
                startStopBtn.isEnabled = true
            } ?: run {
                statusText.text = "Service not ready. Try again."
            }
        }, 1000)
    }

    private fun onStartStop() {
        val svc = holterService
        if (svc != null && svc.isRecording) {
            // Stop
            val intent = Intent(this, HolterService::class.java).apply {
                action = HolterService.ACTION_STOP
            }
            startService(intent)
            startStopBtn.text = "Start Recording"
            eventBtn.isEnabled = false
            shareBtn.isEnabled = true
            statusText.text = "Recording stopped."
            updateHandler.removeCallbacksAndMessages(null)
        } else {
            // Start
            val intent = Intent(this, HolterService::class.java).apply {
                action = HolterService.ACTION_START
                putExtra(HolterService.EXTRA_ADDRESS, "34:81:F4:1C:3F:C1")
            }
            startForegroundService(intent)
            startStopBtn.text = "Stop Recording"
            eventBtn.isEnabled = true
            shareBtn.isEnabled = false
            statusText.text = "Recording..."
            startUiUpdates()
        }
    }

    private fun onAddEvent() {
        val input = EditText(this).apply { hint = "Describe what happened..." }
        AlertDialog.Builder(this)
            .setTitle("Add Event")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val intent = Intent(this, HolterService::class.java).apply {
                        action = HolterService.ACTION_ADD_EVENT
                        putExtra(HolterService.EXTRA_EVENT_TEXT, text)
                        putExtra(HolterService.EXTRA_EVENT_TAG, "note")
                    }
                    startService(intent)
                    Toast.makeText(this, "Event saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Dizziness") { _, _ ->
                sendQuickEvent("Головокружение", "dizziness")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendQuickEvent(text: String, tag: String) {
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_ADD_EVENT
            putExtra(HolterService.EXTRA_EVENT_TEXT, text)
            putExtra(HolterService.EXTRA_EVENT_TAG, tag)
        }
        startService(intent)
        Toast.makeText(this, "Event: $text", Toast.LENGTH_SHORT).show()
    }

    private fun onShare() {
        val store = RecordingStore(this)
        val xml = store.exportToXml(-1) // last session
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

    // --- UI updates ---

    private fun startUiUpdates() {
        updateHandler.postDelayed(object : Runnable {
            override fun run() {
                holterService?.let { svc ->
                    hrText.text = "HR: ${if (svc.lastHr > 0) "${svc.lastHr}" else "--"}"
                    batteryText.text = "Battery: ${if (svc.battery >= 0) "${svc.battery}/3" else "--"}" +
                            if (svc.leadOff) " ⚠ LEAD OFF" else ""

                    val elapsed = if (svc.startTime > 0)
                        (System.currentTimeMillis() - svc.startTime) / 1000 else 0
                    val h = elapsed / 3600
                    val m = (elapsed % 3600) / 60
                    durationText.text = "Duration: ${h}h ${m}m | ${svc.sampleCount} samples"

                    statusText.text = when (svc.btState) {
                        dev.snapecg.holter.bluetooth.DeviceManager.State.CONNECTED -> "Recording..."
                        dev.snapecg.holter.bluetooth.DeviceManager.State.RECONNECTING -> "⚠ Reconnecting BT..."
                        else -> "BT: ${svc.btState}"
                    }
                }
                if (holterService?.isRecording == true) {
                    updateHandler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    // --- Service binding ---

    private val holterConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            holterService = (service as HolterService.LocalBinder).getService()
            connectorService?.holterService = holterService
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            holterService = null
            bound = false
        }
    }

    private val connectorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectorService = (service as ConnectorService.LocalBinder).getService()
            connectorService?.holterService = holterService
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            connectorService = null
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
        updateHandler.removeCallbacksAndMessages(null)
        if (bound) unbindService(holterConnection)
        super.onDestroy()
    }
}
