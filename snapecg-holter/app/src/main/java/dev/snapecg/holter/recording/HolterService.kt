package dev.snapecg.holter.recording

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.snapecg.holter.bluetooth.DeviceManager
import dev.snapecg.holter.bluetooth.QRSDetector
import dev.snapecg.holter.ui.MainActivity
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground service for continuous ECG recording.
 *
 * Resilience:
 * - START_STICKY: auto-restart on kill
 * - Partial wake lock: survives screen off
 * - Writes every second: minimal data loss on crash
 * - Reconnects Bluetooth automatically via DeviceManager
 */
class HolterService : Service(), DeviceManager.Listener {

    companion object {
        private const val TAG = "HolterService"
        private const val CHANNEL_ID = "holter_recording"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_INTERVAL_MS = 1000L

        const val ACTION_START = "dev.snapecg.holter.START"
        const val ACTION_STOP = "dev.snapecg.holter.STOP"
        const val ACTION_CONNECT = "dev.snapecg.holter.CONNECT"
        const val ACTION_DISCONNECT = "dev.snapecg.holter.DISCONNECT"
        const val ACTION_ADD_EVENT = "dev.snapecg.holter.ADD_EVENT"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_EVENT_TEXT = "event_text"
        const val EXTRA_EVENT_TAG = "event_tag"
    }

    private var deviceManager: DeviceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var store: RecordingStore? = null
    private var sessionId: Long = -1
    private val qrsDetector = QRSDetector()

    // Live stats
    var isRecording = false; private set
    var sampleCount = 0L; private set
    var startTime = 0L; private set
    var lastHr = 0; private set
    var battery = -1; private set
    var leadOff = false; private set
    var btState = DeviceManager.State.DISCONNECTED; private set
    var firmware = ""; private set

    // Buffer for batched writes
    private val sampleBuffer = mutableListOf<Int>()
    private val flushHandler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flushSamples() }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Binder for UI
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): HolterService = this@HolterService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        store = RecordingStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                connectOnly(address)
            }
            ACTION_DISCONNECT -> {
                disconnectOnly()
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                startRecording(address)
            }
            ACTION_STOP -> stopRecording()
            ACTION_ADD_EVENT -> {
                val text = intent.getStringExtra(EXTRA_EVENT_TEXT) ?: ""
                val tag = intent.getStringExtra(EXTRA_EVENT_TAG) ?: "note"
                if (text.isNotEmpty()) addEvent(text, tag)
            }
        }
        return START_STICKY
    }

    // --- BT connect without recording ---

    private fun connectOnly(address: String) {
        if (deviceManager != null) return // already connected or connecting
        deviceManager = DeviceManager(this).apply {
            listener = this@HolterService
            connect(address)
        }
        Log.i(TAG, "Connecting to $address (no recording)")
    }

    private fun disconnectOnly() {
        if (isRecording) return // use ACTION_STOP instead
        deviceManager?.disconnect()
        deviceManager = null
        btState = DeviceManager.State.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Recording lifecycle ---

    private fun startRecording(address: String) {
        if (isRecording) return

        // Foreground notification
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        // Wake lock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snapecg:holter")
        wakeLock?.acquire()

        // Start session in database
        sessionId = store!!.createSession(address)
        startTime = System.currentTimeMillis()
        sampleCount = 0
        isRecording = true

        // Connect to device (or reuse existing connection)
        if (deviceManager == null) {
            deviceManager = DeviceManager(this).apply {
                listener = this@HolterService
                connect(address)
            }
        } else if (btState == DeviceManager.State.CONNECTED) {
            // Already connected from CONNECT phase — start streaming
            serviceScope.launch {
                deviceManager?.initialize()
                delay(300)
                deviceManager?.startStreaming()
                withContext(Dispatchers.Main) {
                    updateNotification("Recording...")
                }
                store?.logStatus(sessionId, "bt_connected")
            }
        }

        // Start periodic flush
        flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)

        Log.i(TAG, "Recording started: session=$sessionId address=$address")
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // Capture for async cleanup, then nullify so re-connect works
        val dm = deviceManager
        deviceManager = null

        // Stop streaming off main thread
        serviceScope.launch {
            dm?.stopStreaming()
            delay(100)
            dm?.disconnect()
        }

        // Flush remaining samples
        flushSamples()
        flushHandler.removeCallbacks(flushRunnable)

        // Close session
        store?.closeSession(sessionId, sampleCount)

        // Release wake lock
        wakeLock?.release()
        wakeLock = null

        // Stop foreground
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Recording stopped: $sampleCount samples")
    }

    // --- DeviceManager.Listener ---

    override fun onStateChanged(state: DeviceManager.State) {
        btState = state
        when (state) {
            DeviceManager.State.CONNECTED -> {
                serviceScope.launch {
                    deviceManager?.initialize()
                    delay(300)
                    deviceManager?.startStreaming()
                    withContext(Dispatchers.Main) {
                        updateNotification("Recording...")
                    }
                    store?.logStatus(sessionId, "bt_connected")
                }
            }
            DeviceManager.State.RECONNECTING -> {
                updateNotification("Reconnecting...")
                store?.logStatus(sessionId, "bt_disconnected")
            }
            else -> {}
        }
    }

    override fun onEcgSample(sample: Int, leadOff: Boolean) {
        if (!isRecording) return
        synchronized(sampleBuffer) {
            sampleBuffer.add(sample)
        }
        sampleCount++
        this.leadOff = leadOff

        // QRS detection — sample is baseline-subtracted, restore ADC baseline
        val hr = qrsDetector.process(sample + 2048)
        if (hr > 0) lastHr = hr
    }

    override fun onBattery(level: Int) {
        battery = level
    }

    override fun onVersionReceived(version: String) {
        firmware = version
    }

    override fun onDeviceInfo(type: Int) {}
    override fun onAnswerReceived(code: Int) {}

    override fun onConnectionLostTooLong(seconds: Int) {
        if (!isRecording) return
        updateNotification("⚠ BT lost ${seconds}s! Move closer to device.")
        // Vibrate alert
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
        }
    }

    // --- Event diary ---

    fun addEvent(text: String, tag: String = "note") {
        store?.addEvent(sessionId, sampleCount, text, tag)
        Log.i(TAG, "Event: [$tag] $text (at sample $sampleCount)")
    }

    // --- Data flush ---

    private fun flushSamples() {
        val toWrite: List<Int>
        synchronized(sampleBuffer) {
            if (sampleBuffer.isEmpty()) {
                if (isRecording) flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
                return
            }
            toWrite = sampleBuffer.toList()
            sampleBuffer.clear()
        }

        store?.writeSamples(sessionId, toWrite)

        // Update notification
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        val hours = elapsed / 3600
        val mins = (elapsed % 3600) / 60
        val statusText = buildString {
            append(String.format("%dh %02dm", hours, mins))
            if (lastHr > 0) append(" | HR: $lastHr")
            if (battery >= 0) append(" | Bat: $battery/3")
            if (leadOff) append(" | ⚠ LEAD OFF")
        }
        updateNotification(statusText)

        if (isRecording) flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Holter Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous ECG recording"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnapECG Holter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        serviceScope.cancel()
        super.onDestroy()
    }
}
