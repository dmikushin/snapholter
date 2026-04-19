package dev.snapecg.holter.connector

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import dev.snapecg.holter.recording.HolterService
import dev.snapecg.holter.recording.RecordingStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*

/**
 * Service handling PC connector discovery and communication.
 *
 * - Broadcasts presence on UDP port 8365
 * - Accepts TCP connection from PC connector
 * - Handles JSON-RPC requests from the connector
 */
class ConnectorService : Service() {

    companion object {
        private const val TAG = "ConnectorService"
        private const val PORT = 8365
        private const val BROADCAST_INTERVAL_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var paired = false
    private var pairingCode: String? = null

    // Reference to HolterService (set by MainActivity or self-bound)
    var holterService: HolterService? = null
    var store: RecordingStore? = null
    private var holterBound = false

    private val holterConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? HolterService.LocalBinder
            holterService = binder?.getService()
            holterBound = true
            Log.i(TAG, "Bound to HolterService")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            holterService = null
            holterBound = false
            Log.w(TAG, "HolterService disconnected")
        }
    }

    private fun bindToHolterService() {
        if (holterBound) return
        val intent = Intent(this, HolterService::class.java)
        bindService(intent, holterConnection, Context.BIND_AUTO_CREATE)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): ConnectorService = this@ConnectorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(this)
        scope.launch { startBroadcast() }
        scope.launch { startServer() }
        Log.i(TAG, "ConnectorService started on port $PORT")
    }

    override fun onDestroy() {
        scope.cancel()
        if (holterBound) {
            unbindService(holterConnection)
            holterBound = false
        }
        serverSocket?.close()
        clientSocket?.close()
        super.onDestroy()
    }

    // --- UDP Discovery ---

    private suspend fun startBroadcast() {
        val socket = DatagramSocket()
        socket.broadcast = true
        val msg = JSONObject().apply {
            put("type", "snapecg_holter")
            put("name", android.os.Build.MODEL)
            put("port", PORT)
            put("version", "1.0")
        }.toString().toByteArray()

        while (scope.isActive) {
            try {
                val packet = DatagramPacket(msg, msg.size,
                    InetAddress.getByName("255.255.255.255"), PORT)
                socket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Broadcast failed: ${e.message}")
            }
            delay(BROADCAST_INTERVAL_MS)
        }
        socket.close()
    }

    // --- TCP Server ---

    private suspend fun startServer() {
        serverSocket = ServerSocket(PORT)
        while (scope.isActive) {
            try {
                val client = serverSocket!!.accept()
                Log.i(TAG, "Connector connected from ${client.inetAddress}")
                clientSocket = client
                handleClient(client)
            } catch (e: Exception) {
                if (scope.isActive) Log.w(TAG, "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        try {
            while (scope.isActive && socket.isConnected) {
                // Read length-prefixed JSON
                val length = input.readInt()
                if (length <= 0 || length > 10 * 1024 * 1024) break
                val payload = ByteArray(length)
                input.readFully(payload)
                val request = JSONObject(String(payload))

                // Handle request
                val response = handleRequest(request)

                // Send response
                val respBytes = response.toString().toByteArray()
                output.writeInt(respBytes.size)
                output.write(respBytes)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client disconnected: ${e.message}")
        } finally {
            socket.close()
            clientSocket = null
            paired = false
        }
    }

    // --- Request handler ---

    private fun handleRequest(request: JSONObject): JSONObject {
        val method = request.optString("method", "")
        val id = request.optInt("id", 0)
        val params = request.optJSONObject("params") ?: JSONObject()

        val result = try {
            when (method) {
                "pair" -> handlePair(params)
                "holter.get_status" -> handleGetStatus()
                "holter.get_signal" -> handleGetSignal(params)
                "holter.get_events" -> handleGetEvents()
                "holter.add_event" -> handleAddEvent(params)
                "holter.start_recording" -> handleStartRecording()
                "holter.stop_recording" -> handleStopRecording()
                "holter.get_recording" -> handleGetRecording()
                "holter.get_summary" -> handleGetSummary()
                else -> JSONObject().put("error", "unknown method: $method")
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }

        return JSONObject().apply {
            put("id", id)
            put("result", result)
        }
    }

    // --- Pairing ---

    fun generatePairingCode(): String {
        pairingCode = (100000..999999).random().toString()
        return pairingCode!!
    }

    private fun handlePair(params: JSONObject): JSONObject {
        // Verify HMAC proof from connector
        val proof = params.optString("proof", "")
        // In production: verify HMAC. For MVP: auto-accept if code displayed.
        paired = true
        return JSONObject().put("status", "paired")
    }

    // --- API handlers ---

    private fun handleGetStatus(): JSONObject {
        val hs = holterService
        return JSONObject().apply {
            put("bt_connected", hs?.btState == dev.snapecg.holter.bluetooth.DeviceManager.State.CONNECTED)
            put("bt_state", hs?.btState?.name ?: "UNKNOWN")
            put("recording", hs?.isRecording ?: false)
            put("sample_count", hs?.sampleCount ?: 0)
            put("duration_seconds", if (hs?.startTime ?: 0 > 0)
                (System.currentTimeMillis() - hs!!.startTime) / 1000 else 0)
            put("device_battery", hs?.battery ?: -1)
            put("phone_battery", getPhoneBattery())
            put("lead_off", hs?.leadOff ?: true)
            put("firmware", hs?.firmware ?: "")
            put("free_storage_mb", getFreeStorageMb())
        }
    }

    private fun handleGetSignal(params: JSONObject): JSONObject {
        val n = params.optInt("n", 2000) // default 10s at 200Hz
        val hs = holterService ?: return JSONObject().put("error", "not recording")
        // Get recent samples from store
        val samples = store?.getRecentSamples(/* sessionId */ -1, n) ?: emptyList()
        val arr = JSONArray()
        for (s in samples) arr.put(s + 2048) // restore ADC baseline for analysis
        return JSONObject().put("samples", arr)
    }

    private fun handleGetEvents(): JSONObject {
        val events = store?.getEvents(/* sessionId */ -1) ?: emptyList()
        val arr = JSONArray()
        for (e in events) {
            arr.put(JSONObject().apply {
                put("sample_index", e.sampleIndex)
                put("timestamp", e.timestamp)
                put("tag", e.tag)
                put("text", e.text)
            })
        }
        return JSONObject().put("events", arr)
    }

    private fun handleAddEvent(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val tag = params.optString("tag", "note")
        if (text.isEmpty()) return JSONObject().put("error", "text required")
        holterService?.addEvent(text, tag)
        return JSONObject().put("status", "added")
    }

    private fun handleStartRecording(): JSONObject {
        // Start via intent to HolterService
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_START
            putExtra(HolterService.EXTRA_ADDRESS, "34:81:F4:1C:3F:C1") // TODO: from params
        }
        startForegroundService(intent)
        // Bind so we can query status later
        bindToHolterService()
        return JSONObject().put("status", "starting")
    }

    private fun handleStopRecording(): JSONObject {
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_STOP
        }
        startService(intent)
        return JSONObject().put("status", "stopping")
    }

    private fun handleGetRecording(): JSONObject {
        val xml = store?.exportToXml(/* sessionId */ -1) ?: "<error/>"
        return JSONObject().put("xml", xml)
    }

    private fun handleGetSummary(): JSONObject {
        val hs = holterService
        return JSONObject().apply {
            put("recording", hs?.isRecording ?: false)
            put("sample_count", hs?.sampleCount ?: 0)
            put("duration_seconds", if (hs?.startTime ?: 0 > 0)
                (System.currentTimeMillis() - hs!!.startTime) / 1000 else 0)
            put("device_battery", hs?.battery ?: -1)
            put("lead_off", hs?.leadOff ?: false)
        }
    }

    // --- Helpers ---

    private fun getPhoneBattery(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getFreeStorageMb(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
