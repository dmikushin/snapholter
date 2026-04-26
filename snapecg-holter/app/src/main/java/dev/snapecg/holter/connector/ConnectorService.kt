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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
        // AES-GCM parameters (must match snapecg-connector/protocol.py)
        private const val GCM_NONCE_BYTES = 12     // 96-bit nonce per NIST SP 800-38D
        private const val GCM_TAG_BITS = 128       // 16-byte authentication tag
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secureRandom = SecureRandom()
    private var clientSocket: Socket? = null

    // --- Pairing / session crypto state (per TCP session) ---
    // Pairing protocol (matches snapecg-connector/protocol.py):
    //   1. On every accepted TCP session we generate a fresh 6-digit code,
    //      log it (PAIRING_CODE=NNNNNN), and require the user to type it
    //      into the PC connector.
    //   2. PC sends `pair` request with salt + proof = HMAC-SHA256(code, salt).
    //   3. We verify the HMAC in constant time. On match we derive a
    //      session key (PBKDF2-HMAC-SHA256, 100k iters, 32 bytes) which is
    //      the same key the PC derives — used by the next commit for AES-GCM.
    //   4. Until `paired` flips true, every non-`pair` RPC method is
    //      rejected with `{error: "not paired"}`.
    private var pendingPairCode: String? = null
    private var paired = false
    /** Set after successful pair; consumed by AES-GCM in a follow-up commit. */
    var sessionKey: ByteArray? = null; private set

    // Observable connector state (based on port scan discovery)
    var isConnectorConnected = false; private set
    var connectorAddress: String? = null; private set

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
        scope.launch { scanForConnector() }
        Log.i(TAG, "ConnectorService started, scanning for connector")
    }

    override fun onDestroy() {
        scope.cancel()
        if (holterBound) {
            unbindService(holterConnection)
            holterBound = false
        }
        clientSocket?.close()
        super.onDestroy()
    }

    // --- Connector Discovery (port scan) ---

    private suspend fun scanForConnector() {
        while (scope.isActive) {
            try {
                if (clientSocket != null && clientSocket?.isConnected == true) {
                    // Already connected — just check it's alive
                    delay(5000)
                    continue
                }

                val localIp = getLocalIp()
                if (localIp == null) {
                    Log.w(TAG, "Cannot determine local IP, retrying...")
                    delay(5000)
                    continue
                }
                val prefix = localIp.substringBeforeLast('.')
                Log.d(TAG, "Scanning $prefix.* for connector (local=$localIp)")
                val found = scanSubnet(prefix, localIp)
                if (found != null) {
                    Log.i(TAG, "Connector found at $found, connecting...")
                    connectorAddress = found
                    isConnectorConnected = true
                    connectToConnector(found)
                } else {
                    if (isConnectorConnected) {
                        Log.i(TAG, "Connector lost")
                    }
                    isConnectorConnected = false
                    connectorAddress = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Discovery error: ${e.message}")
                isConnectorConnected = false
                connectorAddress = null
            }
            delay(5000)
        }
    }

    private suspend fun connectToConnector(address: String) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(address, PORT), 3000)
            clientSocket = socket
            Log.i(TAG, "Connected to connector at $address:$PORT")
            handleConnectorSession(socket)
        } catch (e: Exception) {
            Log.w(TAG, "Connection to connector failed: ${e.message}")
            isConnectorConnected = false
            clientSocket = null
        }
    }

    private suspend fun handleConnectorSession(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        // Fresh pairing state per session — old codes don't carry over.
        startNewPairingSession()

        try {
            while (scope.isActive && socket.isConnected) {
                val length = input.readInt()
                if (length <= 0 || length > 10 * 1024 * 1024) break
                val payload = ByteArray(length)
                input.readFully(payload)

                // Capture pair-state BEFORE handling: a successful `pair`
                // request comes in plaintext and must be answered in
                // plaintext (the PC has no key yet at that point).
                val keyForRequest = if (paired) sessionKey else null
                val plaintext = if (keyForRequest != null) {
                    decryptGcm(keyForRequest, payload)
                } else payload
                val request = JSONObject(String(plaintext))

                val response = handleRequest(request)
                val respBytes = response.toString().toByteArray()

                val outgoing = if (keyForRequest != null) {
                    encryptGcm(keyForRequest, respBytes)
                } else respBytes
                output.writeInt(outgoing.size)
                output.write(outgoing)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connector session ended: ${e.message}")
        } finally {
            socket.close()
            clientSocket = null
            isConnectorConnected = false
            // Wipe credentials on disconnect — every reconnect must re-pair.
            endPairingSession()
            Log.i(TAG, "Disconnected from connector")
        }
    }

    private suspend fun scanSubnet(prefix: String, localIp: String): String? {
        val jobs = (1..254).mapNotNull { i ->
            val ip = "$prefix.$i"
            if (ip == localIp) return@mapNotNull null
            scope.async {
                try {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(ip, PORT), 500)
                    sock.close()
                    ip
                } catch (_: Exception) {
                    null
                }
            }
        }
        val results = jobs.awaitAll()
        return results.filterNotNull().firstOrNull()
    }

    private fun getLocalIp(): String? {
        try {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIp failed: ${e.message}")
        }
        return null
    }

    // --- Request handler ---

    private fun handleRequest(request: JSONObject): JSONObject {
        val method = request.optString("method", "")
        val id = request.optInt("id", 0)
        val params = request.optJSONObject("params") ?: JSONObject()

        val result = try {
            // Gate: every method except `pair` requires a successful HMAC pair first.
            if (method != "pair" && !paired) {
                JSONObject().put("error", "not paired")
            } else when (method) {
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

    /** Generate a fresh code, reset paired state, log code for the user. */
    private fun startNewPairingSession() {
        pendingPairCode = (100000..999999).random().toString()
        paired = false
        sessionKey = null
        // TODO: surface code in UI/notification. For now: adb logcat -s ConnectorService
        Log.i(TAG, "PAIRING_CODE=$pendingPairCode  (enter on PC connector)")
    }

    private fun endPairingSession() {
        pendingPairCode = null
        paired = false
        sessionKey = null
    }

    private fun handlePair(params: JSONObject): JSONObject {
        val code = pendingPairCode
            ?: return JSONObject().put("error", "no pairing in progress")
        val saltHex = params.optString("salt", "")
        val proofHex = params.optString("proof", "")
        if (saltHex.isEmpty() || proofHex.isEmpty()) {
            return JSONObject().put("error", "salt and proof required")
        }
        val salt = hexToBytes(saltHex)
            ?: return JSONObject().put("error", "invalid salt")
        val proof = hexToBytes(proofHex)
            ?: return JSONObject().put("error", "invalid proof")

        val expected = hmacSha256(code.toByteArray(Charsets.UTF_8), salt)
        if (!MessageDigest.isEqual(expected, proof)) {
            Log.w(TAG, "Pairing rejected: HMAC mismatch")
            return JSONObject().put("error", "invalid proof")
        }

        sessionKey = pbkdf2(code, salt, iterations = 100_000, keyBytes = 32)
        paired = true
        pendingPairCode = null  // one-shot — code can't be reused
        Log.i(TAG, "Paired successfully (session key derived)")
        return JSONObject().put("status", "paired")
    }

    // --- Crypto helpers ---

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBytes * 8)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Encrypt with AES-256-GCM. Wire layout: [12-byte nonce] [ciphertext + 16-byte tag].
     * Matches the Python side in snapecg-connector/protocol.py.
     */
    private fun encryptGcm(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,
                 SecretKeySpec(key, "AES"),
                 GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    private fun decryptGcm(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) {
            throw IllegalArgumentException("ciphertext shorter than nonce + GCM tag")
        }
        val nonce = blob.copyOfRange(0, GCM_NONCE_BYTES)
        val ct = blob.copyOfRange(GCM_NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,
                 SecretKeySpec(key, "AES"),
                 GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        return cipher.doFinal(ct)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
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
