package dev.snapecg.holter.connector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.snapecg.holter.recording.HolterService
import dev.snapecg.holter.recording.RecordingStore
import dev.snapecg.holter.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private const val PAIR_CHANNEL_ID = "snapecg_pairing"
        private const val PAIR_NOTIFICATION_ID = 2  // 1 is HolterService recording
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var clientSocket: Socket? = null

    // --- Pairing / session crypto state (per TCP session) ---
    // Pairing protocol (matches snapecg-connector/protocol.py):
    //   - Fresh pair: phone generates a 6-digit code (logged as
    //     PAIRING_CODE=NNNNNN), PC sends `pair` with proof = HMAC-SHA256(
    //     code, salt). On match we derive sessionKey via PBKDF2 and persist
    //     it (encrypted, see PairingStore) so a quick reconnect skips
    //     re-entering the code.
    //   - Resume: if a key was saved for this peer within MAX_AGE_MS, PC
    //     sends `resume` with proof = HMAC-SHA256(savedKey, salt). On
    //     match we restore sessionKey and skip the code prompt.
    //   - Until paired flips true, every non-{pair, resume} method is
    //     rejected with {error: "not paired"}.
    private var pendingPairCode: String? = null
    private var paired = false
    /** Address of the connected PC, used for keying PairingStore. */
    private var currentPeerAddress: String? = null
    /** Set after successful pair or resume; consumed for AES-GCM. */
    var sessionKey: ByteArray? = null; private set
    private val pairingStore by lazy { PairingStore(this) }

    // Observable connector state. Direct field reads stay available for the
    // RPC handlers; UI watchers prefer the StateFlow so they don't need to
    // poll at 1 Hz.
    @Volatile var isConnectorConnected = false; private set
    @Volatile var connectorAddress: String? = null; private set

    data class ConnectorUiState(val connected: Boolean, val address: String?)

    private val _connectorState = MutableStateFlow(ConnectorUiState(false, null))
    val connectorState: StateFlow<ConnectorUiState> = _connectorState.asStateFlow()

    private fun setConnectorState(connected: Boolean, address: String?) {
        isConnectorConnected = connected
        connectorAddress = address
        _connectorState.value = ConnectorUiState(connected, address)
    }

    // Reference to HolterService (set by MainActivity or self-bound)
    var holterService: HolterService? = null
    private var holterBound = false

    /** Convenience: the recording-store lives on HolterService so we don't
     *  need a second SQLiteOpenHelper against the same on-disk database. */
    private val store: RecordingStore? get() = holterService?.store

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
        createPairingNotificationChannel()
        // Bind eagerly so RPC handlers always see the same RecordingStore /
        // live Holter state — no point in this service running otherwise.
        // HolterService.onCreate is cheap and doesn't start the foreground
        // until a recording is actually requested.
        bindToHolterService()
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
        cancelPairingNotification()
        super.onDestroy()
    }

    // --- Connector Discovery (UDP broadcast listener) ---
    //
    // The PC connector advertises itself by broadcasting a JSON announcement
    // every 2 seconds (snapecg-connector/protocol.py: broadcast_presence).
    // We listen passively on UDP/8365 and grab the sender IP from the first
    // valid packet — no subnet port-scanning, no parallel TCP probes that
    // look like nmap to corporate firewalls.

    private suspend fun scanForConnector() {
        // Some Wi-Fi chipsets / OEMs filter broadcast packets to userspace
        // unless an explicit lock is held. CHANGE_WIFI_MULTICAST_STATE is
        // declared in the manifest already.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mcastLock = wifi?.createMulticastLock("snapecg-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(PORT))
                sock.soTimeout = 3000  // wake up periodically to honour cancel

                val buf = ByteArray(2048)
                while (scope.isActive) {
                    // While connected: don't poll the network — just wait.
                    if (clientSocket?.isConnected == true) {
                        delay(2000); continue
                    }

                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "Discovery socket error: ${e.message}")
                        delay(1000); continue
                    }

                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val sender = packet.address.hostAddress ?: continue
                    val type = try {
                        JSONObject(payload).optString("type", "")
                    } catch (_: Exception) { "" }
                    if (type != "snapecg_connector") continue

                    Log.i(TAG, "Connector announced from $sender")
                    setConnectorState(connected = true, address = sender)
                    try {
                        connectToConnector(sender)
                    } finally {
                        // connection lifecycle ended — keep listening.
                        setConnectorState(connected = false, address = null)
                    }
                }
            }
        } finally {
            try { mcastLock?.release() } catch (_: Exception) {}
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
            setConnectorState(connected = false, address = null)
            clientSocket = null
        }
    }

    private suspend fun handleConnectorSession(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        val peerIp = socket.inetAddress?.hostAddress ?: "unknown"
        // Fresh pairing state per session — old codes don't carry over.
        startNewPairingSession(peerIp)

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
            setConnectorState(connected = false, address = null)
            // Wipe credentials on disconnect — every reconnect must re-pair.
            endPairingSession()
            Log.i(TAG, "Disconnected from connector")
        }
    }

    // --- Request handler ---

    private fun handleRequest(request: JSONObject): JSONObject {
        val method = request.optString("method", "")
        val id = request.optInt("id", 0)
        val params = request.optJSONObject("params") ?: JSONObject()

        val result = try {
            // Gate: pair and resume are the only methods that can run before
            // sessionKey is established. Everything else is bounced.
            if (method != "pair" && method != "resume" && !paired) {
                JSONObject().put("error", "not paired")
            } else when (method) {
                "pair" -> handlePair(params)
                "resume" -> handleResume(params)
                "holter.get_status" -> handleGetStatus()
                "holter.get_signal" -> handleGetSignal(params)
                "holter.get_events" -> handleGetEvents()
                "holter.add_event" -> handleAddEvent(params)
                "holter.start_recording" -> handleStartRecording(params)
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
    private fun startNewPairingSession(peerAddress: String) {
        currentPeerAddress = peerAddress
        val code = (100000..999999).random().toString()
        pendingPairCode = code
        paired = false
        sessionKey = null
        Log.i(TAG, "PAIRING_CODE=$code  (enter on PC connector)")
        // Heads-up to the PC: a saved key may exist; resume is worth trying first.
        if (pairingStore.loadIfFresh(peerAddress) != null) {
            Log.i(TAG, "Saved pairing exists for $peerAddress; PC may attempt resume")
        }
        showPairingNotification(code, peerAddress)
    }

    private fun endPairingSession() {
        pendingPairCode = null
        currentPeerAddress = null
        paired = false
        sessionKey = null
        cancelPairingNotification()
    }

    private fun handlePair(params: JSONObject): JSONObject {
        val code = pendingPairCode
            ?: return JSONObject().put("error", "no pairing in progress")
        val saltHex = params.optString("salt", "")
        val proofHex = params.optString("proof", "")
        if (saltHex.isEmpty() || proofHex.isEmpty()) {
            return JSONObject().put("error", "salt and proof required")
        }
        val salt = ConnectorCrypto.hexToBytes(saltHex)
            ?: return JSONObject().put("error", "invalid salt")
        val proof = ConnectorCrypto.hexToBytes(proofHex)
            ?: return JSONObject().put("error", "invalid proof")

        if (!ConnectorCrypto.verifyProof(code, salt, proof)) {
            Log.w(TAG, "Pairing rejected: HMAC mismatch")
            return JSONObject().put("error", "invalid proof")
        }

        val key = ConnectorCrypto.deriveSessionKey(code, salt)
        sessionKey = key
        paired = true
        pendingPairCode = null  // one-shot — code can't be reused
        currentPeerAddress?.let { pairingStore.save(it, key) }
        cancelPairingNotification()
        Log.i(TAG, "Paired successfully (session key derived & persisted)")
        return JSONObject().put("status", "paired")
    }

    /**
     * Resume an existing pair using the previously-saved session key for
     * this peer. PC sends `salt` + proof = HMAC-SHA256(savedKey, salt);
     * we look up our copy of savedKey and recompute. On match the
     * sessionKey is restored and AES-GCM resumes — no code re-entry.
     */
    private fun handleResume(params: JSONObject): JSONObject {
        val peer = currentPeerAddress
            ?: return JSONObject().put("error", "no peer address; reconnect first")
        val saltHex = params.optString("salt", "")
        val proofHex = params.optString("proof", "")
        val salt = ConnectorCrypto.hexToBytes(saltHex)
            ?: return JSONObject().put("error", "invalid salt")
        val proof = ConnectorCrypto.hexToBytes(proofHex)
            ?: return JSONObject().put("error", "invalid proof")

        val saved = pairingStore.loadIfFresh(peer)
            ?: return JSONObject().put("error", "no saved pairing for this peer; needs_pair")

        val expected = ConnectorCrypto.hmacSha256(saved, salt)
        if (!java.security.MessageDigest.isEqual(expected, proof)) {
            Log.w(TAG, "Resume rejected: HMAC mismatch (PC's stored key has drifted)")
            return JSONObject().put("error", "invalid proof; needs_pair")
        }

        sessionKey = saved
        paired = true
        pendingPairCode = null
        cancelPairingNotification()
        Log.i(TAG, "Resumed previous pair with $peer (skipped code re-entry)")
        return JSONObject().put("status", "resumed")
    }

    // --- Pairing notification ---
    //
    // The pairing code is a per-session secret the user must read off the
    // phone and type into the PC connector. Without UI it lived only in
    // adb logcat, which is invisible to a normal user. A high-importance
    // notification surfaces it without requiring the app to be foregrounded.

    private fun createPairingNotificationChannel() {
        val channel = NotificationChannel(
            PAIR_CHANNEL_ID, "Pairing prompts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows the 6-digit code to type into the PC connector"
            setShowBadge(true)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun showPairingNotification(code: String, peerAddress: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, PAIR_CHANNEL_ID)
            .setContentTitle("SnapECG pairing code")
            .setContentText("$code  —  type on PC ($peerAddress)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Code: $code\n\nType this on the PC running snapecg-connector " +
                "(asking for it via --pair). Connector at $peerAddress."
            ))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(PAIR_NOTIFICATION_ID, notif)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied (API 33+). Fall back to log only.
            Log.w(TAG, "Cannot post pairing notification: ${e.message}")
        }
    }

    private fun cancelPairingNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(PAIR_NOTIFICATION_ID)
        } catch (_: Exception) { /* ignore */ }
    }

    // Thin delegates so callers in this file stay readable; logic is in ConnectorCrypto.
    private fun encryptGcm(key: ByteArray, plaintext: ByteArray) =
        ConnectorCrypto.encryptGcm(key, plaintext)
    private fun decryptGcm(key: ByteArray, blob: ByteArray) =
        ConnectorCrypto.decryptGcm(key, blob)

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
        // Empty list if HolterService isn't bound or there's no recording yet.
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

    private fun handleStartRecording(params: JSONObject = JSONObject()): JSONObject {
        // Address can be passed by the AI agent; otherwise we resolve it the
        // same way the UI does (saved pref → paired-device auto-detect →
        // legacy default).
        val address = params.optString("address", "").ifBlank {
            dev.snapecg.holter.bluetooth.DeviceResolver.resolve(this)
        }
        val intent = Intent(this, HolterService::class.java).apply {
            action = HolterService.ACTION_START
            putExtra(HolterService.EXTRA_ADDRESS, address)
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
