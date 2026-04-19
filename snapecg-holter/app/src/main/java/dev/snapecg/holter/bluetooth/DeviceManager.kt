package dev.snapecg.holter.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
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
        private const val RECONNECT_INITIAL_MS = 5000L
        private const val RECONNECT_MAX_MS = 30000L
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

    private var socket: BluetoothSocket? = null
    private var device: BluetoothDevice? = null
    private var address: String? = null
    private val parser = StreamParser()
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var shouldReconnect = true
    private var disconnectedSince: Long = 0

    // --- Public API ---

    fun connect(macAddress: String) {
        address = macAddress
        shouldReconnect = true
        disconnectedSince = 0

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
            socket?.outputStream?.write(data)
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: ${e.message}")
            onConnectionLost()
        }
    }

    fun initialize() {
        send(Protocol.makeGetVersion())
        Thread.sleep(150)
        send(Protocol.makeGetDeviceInfo())
        Thread.sleep(150)
        send(Protocol.makeSetTime())
        Thread.sleep(50)
        send(Protocol.makeReadAdjustCoeff())
        Thread.sleep(200)
        send(Protocol.makeSetFilterClose())
        Thread.sleep(50)
    }

    fun startStreaming() = send(Protocol.makeStart())
    fun stopStreaming() = send(Protocol.makeStop())

    // --- Connection ---

    private fun doConnect() {
        setState(State.CONNECTING)
        scope.launch {
            try {
                // Cancel discovery — it interferes with RFCOMM connections
                try {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    btManager.adapter?.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "cancelDiscovery denied: ${e.message}")
                }

                val sock = try {
                    val s = device!!.createRfcommSocketToServiceRecord(SPP)
                    s.connect()
                    s
                } catch (e: IOException) {
                    // Fallback: use reflection to connect on RFCOMM channel 1
                    Log.w(TAG, "SPP UUID connect failed, trying channel 1 fallback")
                    val s = device!!.javaClass
                        .getMethod("createRfcommSocket", Int::class.java)
                        .invoke(device, 1) as BluetoothSocket
                    s.connect()
                    s
                }
                socket = sock
                disconnectedSince = 0
                setState(State.CONNECTED)
                Log.i(TAG, "Connected to $address")
                startReader(sock.inputStream)
            } catch (e: Exception) {
                Log.w(TAG, "Connect failed: ${e.message}")
                onConnectionLost()
            }
        }
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

        setState(State.RECONNECTING)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delay = RECONNECT_INITIAL_MS
            while (shouldReconnect && state != State.CONNECTED) {
                Log.i(TAG, "Reconnecting in ${delay}ms...")
                delay(delay)

                // Alert if disconnected too long
                val elapsed = System.currentTimeMillis() - disconnectedSince
                if (elapsed > RECONNECT_ALERT_MS) {
                    withContext(Dispatchers.Main) {
                        listener?.onConnectionLostTooLong((elapsed / 1000).toInt())
                    }
                }

                // Try connecting
                try {
                    try {
                        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        btManager.adapter?.cancelDiscovery()
                    } catch (e: SecurityException) {
                        Log.w(TAG, "cancelDiscovery denied: ${e.message}")
                    }

                    val sock = try {
                        val s = device!!.createRfcommSocketToServiceRecord(SPP)
                        s.connect()
                        s
                    } catch (e: IOException) {
                        Log.w(TAG, "Reconnect SPP UUID failed, trying channel 1 fallback")
                        val s = device!!.javaClass
                            .getMethod("createRfcommSocket", Int::class.java)
                            .invoke(device, 1) as BluetoothSocket
                        s.connect()
                        s
                    }
                    socket = sock
                    disconnectedSince = 0
                    setState(State.CONNECTED)
                    Log.i(TAG, "Reconnected to $address")
                    startReader(sock.inputStream)
                    return@launch
                } catch (e: IOException) {
                    Log.w(TAG, "Reconnect failed: ${e.message}")
                    delay = (delay * 2).coerceAtMost(RECONNECT_MAX_MS)
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
        socket = null
    }
}
