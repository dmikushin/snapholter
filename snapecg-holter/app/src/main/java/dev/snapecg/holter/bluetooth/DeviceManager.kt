package dev.snapecg.holter.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import dev.snapecg.holter.BuildConfig
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Manages Bluetooth SPP connection to SnapECG B10 with auto-reconnect.
 *
 * Resilience features:
 * - Exponential backoff reconnection (5s → 10s → 20s → 30s max)
 * - Connection state machine with callbacks
 * - Thread-safe packet reading
 */
@SuppressLint("MissingPermission")
class DeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceManager"
        private val SPP = UUID.fromString(Protocol.SPP_UUID)
        private const val RECONNECT_INITIAL_MS = 2000L
        private const val RECONNECT_MAX_MS = 15000L
        private const val RECONNECT_ALERT_MS = 30000L // alert after 30s disconnected
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    interface Listener {
        fun onStateChanged(state: State)
        fun onEcgSample(sample: Int, leadOff: Boolean)
        fun onBattery(level: Int)
        fun onVersionReceived(version: String)
        fun onDeviceInfo(type: Int)
        fun onAnswerReceived(code: Int)
        fun onConnectionLostTooLong(seconds: Int)
    }

    var listener: Listener? = null
    var state: State = State.DISCONNECTED; private set
    private var hasConnectedOnce = false

    private var socket: BluetoothSocket? = null
    /** Set when the address is `tcp:host:port` and BuildConfig.DEBUG.
     *  Lets us run the app against the mock B10 server inside the
     *  emulator (no real Bluetooth stack). Always null in release builds. */
    private var tcpSocket: java.net.Socket? = null
    private var device: BluetoothDevice? = null
    private var address: String? = null
    private val parser = StreamParser()
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var shouldReconnect = true
    private var disconnectedSince: Long = 0

    private val currentOutput: OutputStream?
        get() = socket?.outputStream ?: tcpSocket?.outputStream

    // --- Public API ---

    fun connect(macAddress: String) {
        address = macAddress
        shouldReconnect = true
        disconnectedSince = 0

        // Debug-only: if the address is "tcp:host:port", skip the BT stack
        // entirely and use a TCP transport instead. Used by the mock B10
        // server during end-to-end tests on the emulator (Android emulator
        // has no usable Bluetooth). The check is gated on BuildConfig.DEBUG
        // so a release APK can never be tricked into talking to a non-BT
        // peer no matter what ends up in SharedPreferences.
        if (BuildConfig.DEBUG && parseTcpAddress(macAddress) != null) {
            doConnect()
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: run {
            Log.e(TAG, "Bluetooth not available")
            return
        }
        device = adapter.getRemoteDevice(macAddress)
        doConnect()
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        readerJob?.cancel()
        closeSocket()
        setState(State.DISCONNECTED)
    }

    fun send(data: ByteArray) {
        try {
            currentOutput?.write(data)
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: ${e.message}")
            onConnectionLost()
        }
    }

    /**
     * Send the device-init handshake. Runs in the caller's coroutine —
     * uses `delay` (cooperative cancellation, frees the carrier thread)
     * instead of `Thread.sleep` which would block the IO dispatcher
     * thread for 600 ms total.
     */
    suspend fun initialize() {
        send(Protocol.makeGetVersion())
        delay(150)
        send(Protocol.makeGetDeviceInfo())
        delay(150)
        send(Protocol.makeSetTime())
        delay(50)
        send(Protocol.makeReadAdjustCoeff())
        delay(200)
        send(Protocol.makeSetFilterClose())
        delay(50)
    }

    fun startStreaming() = send(Protocol.makeStart())
    fun stopStreaming() = send(Protocol.makeStop())

    // --- Connection ---

    /**
     * Try connecting via SPP UUID, then insecure SPP UUID, then RFCOMM channel 1.
     * Properly closes failed sockets before trying next method.
     */
    private fun tryConnect(): BluetoothSocket {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            btManager.adapter?.cancelDiscovery()
        } catch (_: SecurityException) {}

        // Method 1: standard SPP
        try {
            val s = device!!.createRfcommSocketToServiceRecord(SPP)
            try {
                s.connect()
                return s
            } catch (e: IOException) {
                try { s.close() } catch (_: IOException) {}
                Log.w(TAG, "SPP UUID failed: ${e.message}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "createRfcommSocket failed: ${e.message}")
        }

        // Method 2: insecure SPP
        try {
            val s = device!!.createInsecureRfcommSocketToServiceRecord(SPP)
            try {
                s.connect()
                return s
            } catch (e: IOException) {
                try { s.close() } catch (_: IOException) {}
                Log.w(TAG, "Insecure SPP failed: ${e.message}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "createInsecureRfcommSocket failed: ${e.message}")
        }

        // Method 3: reflection channel 1
        // createRfcommSocket(int) is a hidden API; on Android 9+ it lives
        // behind hidden-API restrictions and may eventually be blocked
        // outright. Log loudly so a future failure here can be traced
        // without rebuilding with USB debugging.
        Log.w(TAG, "Both standard SPP attempts failed — falling back to reflection on createRfcommSocket(int). " +
                "If this throws on a newer Android version, the device's RFCOMM channel will need to be " +
                "advertised with a published SDP record.")
        val s = try {
            device!!.javaClass
                .getMethod("createRfcommSocket", Int::class.java)
                .invoke(device, 1) as BluetoothSocket
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Reflection fallback unavailable on this Android version: ${e.message}")
            throw IOException("All connect methods exhausted; createRfcommSocket(int) is hidden", e)
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Reflection fallback rejected (hidden-API restriction?): ${e.message}")
            throw IOException("All connect methods exhausted; reflection blocked", e)
        }
        try {
            s.connect()
            return s
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw e
        }
    }

    private fun doConnect() {
        setState(State.CONNECTING)
        scope.launch {
            try {
                val input = openTransport()
                disconnectedSince = 0
                parser.reset()
                hasConnectedOnce = true
                setState(State.CONNECTED)
                Log.i(TAG, "Connected to $address")
                startReader(input)
            } catch (e: Exception) {
                Log.w(TAG, "Connect failed: ${e.message}")
                onConnectionLost()
            }
        }
    }

    /** Open whichever transport this address resolves to (TCP shim in debug
     *  builds for "tcp:host:port", otherwise standard RFCOMM). Sets the
     *  matching socket field so currentOutput / closeSocket see it.
     *  Returns the InputStream the reader loop should drain. */
    private fun openTransport(): InputStream {
        val tcp = if (BuildConfig.DEBUG) parseTcpAddress(address) else null
        return if (tcp != null) {
            val s = java.net.Socket()
            s.connect(InetSocketAddress(tcp.first, tcp.second), 3000)
            tcpSocket = s
            Log.i(TAG, "Connected via TCP shim to ${tcp.first}:${tcp.second}")
            s.inputStream
        } else {
            val sock = tryConnect()
            socket = sock
            sock.inputStream
        }
    }

    /** Parse "tcp:host:port" → (host, port). Returns null on any other input. */
    private fun parseTcpAddress(addr: String?): Pair<String, Int>? {
        if (addr == null || !addr.startsWith("tcp:")) return null
        val parts = addr.removePrefix("tcp:").split(":")
        if (parts.size != 2) return null
        val port = parts[1].toIntOrNull() ?: return null
        return parts[0] to port
    }

    private fun onConnectionLost() {
        closeSocket()

        if (disconnectedSince == 0L) {
            disconnectedSince = System.currentTimeMillis()
        }

        if (!shouldReconnect) {
            setState(State.DISCONNECTED)
            return
        }

        // Only show RECONNECTING if we had a successful connection before
        if (hasConnectedOnce) {
            setState(State.RECONNECTING)
        } else {
            setState(State.CONNECTING)
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = RECONNECT_INITIAL_MS
            while (shouldReconnect && state != State.CONNECTED) {
                Log.i(TAG, "Reconnecting in ${delayMs}ms...")
                delay(delayMs)

                // Alert if disconnected too long
                val elapsed = System.currentTimeMillis() - disconnectedSince
                if (elapsed > RECONNECT_ALERT_MS) {
                    withContext(Dispatchers.Main) {
                        listener?.onConnectionLostTooLong((elapsed / 1000).toInt())
                    }
                }

                try {
                    val input = openTransport()
                    disconnectedSince = 0
                    parser.reset()
                    hasConnectedOnce = true
                    setState(State.CONNECTED)
                    Log.i(TAG, "Reconnected to $address")
                    startReader(input)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect failed: ${e.message}")
                    delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                }
            }
        }
    }

    // --- Packet reading ---

    private fun startReader(input: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val packets = parser.feed(buf, n)
                    for (pkt in packets) processPacket(pkt)
                }
            } catch (e: IOException) {
                if (isActive) Log.w(TAG, "Read error: ${e.message}")
            }
            if (shouldReconnect) onConnectionLost()
        }
    }

    private fun processPacket(pkt: StreamParser.Packet) {
        val type = pkt.type
        val raw = pkt.raw
        val off = pkt.offset

        when (type) {
            Protocol.PKT_ECG_1 -> {
                val samples = Protocol.decodeEcg(raw, off, 1)
                if (samples != null) {
                    val leadOff = Protocol.checkLeadOff(raw, off, 1)
                    listener?.onEcgSample(samples[0], leadOff)
                }
            }
            Protocol.PKT_BAT, Protocol.PKT_BAT_TEMP, Protocol.PKT_BAT_TEMP_ACC -> {
                val len = raw[off].toInt() and 0xFF
                val expected = when (type) { 3 -> 3; 4 -> 5; 6 -> 11; else -> -1 }
                if (len == expected && Protocol.verifyChecksum(raw, off)) {
                    listener?.onBattery(raw[off + 2].toInt() and 0xFF)
                }
            }
            Protocol.CMD_GET_VERSION -> {
                Protocol.decodeVersion(raw, off)?.let { listener?.onVersionReceived(it) }
            }
            Protocol.CMD_DEVICE_INFO -> {
                val len = raw[off].toInt() and 0xFF
                if (len == 7) listener?.onDeviceInfo(raw[off + 6].toInt() and 0x03)
            }
            Protocol.CMD_ANSWER -> {
                Protocol.decodeAnswer(raw, off)?.let { listener?.onAnswerReceived(it) }
            }
        }
    }

    // --- Helpers ---

    private fun setState(newState: State) {
        if (state != newState) {
            state = newState
            scope.launch(Dispatchers.Main) { listener?.onStateChanged(newState) }
        }
    }

    private fun closeSocket() {
        readerJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        try { tcpSocket?.close() } catch (_: IOException) {}
        socket = null
        tcpSocket = null
    }
}
