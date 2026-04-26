package dev.snapecg.holter.bluetooth

/**
 * SnapECG B10 protocol: packet encoding, decoding, stream parsing.
 *
 * Packet format: 0xFF | LEN | CMD/TYPE | DATA | CHECKSUM
 *
 * Direct port of snapecg Python package protocol.py
 */

object Protocol {
    const val HEADER: Byte = 0xFF.toByte()
    const val ECG_BASELINE = 2048
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    // Command IDs
    const val CMD_START_STOP = 0x10
    const val CMD_GET_VERSION = 0x12
    const val CMD_DEVICE_INFO = 0x13
    const val CMD_SET_TIME = 0x16
    const val CMD_READ_ADJUST_COEFF = 0x1A
    const val CMD_FILTER_0_5HZ = 0x1D
    const val CMD_ANSWER = 0x1F

    // Packet types
    const val PKT_ECG_1 = 0
    const val PKT_BAT = 3
    const val PKT_BAT_TEMP = 4
    const val PKT_BAT_TEMP_ACC = 6

    fun checksum(data: ByteArray): Byte {
        var s = 0
        for (b in data) s = (s + (b.toInt() and 0xFF)) and 0xFF
        return if (s == 0xFF) 0xFE.toByte() else s.toByte()
    }

    fun makeCommand(cmdId: Int, payload: ByteArray = byteArrayOf(0)): ByteArray {
        val body = ByteArray(payload.size + 2)
        body[0] = (payload.size + 2).toByte()
        body[1] = cmdId.toByte()
        payload.copyInto(body, 2)
        val cs = checksum(body)
        return byteArrayOf(HEADER) + body + byteArrayOf(cs)
    }

    fun makeStart() = makeCommand(CMD_START_STOP, byteArrayOf(1))
    fun makeStop() = makeCommand(CMD_START_STOP, byteArrayOf(0))
    fun makeGetVersion() = makeCommand(CMD_GET_VERSION)
    fun makeGetDeviceInfo() = makeCommand(CMD_DEVICE_INFO)
    fun makeReadAdjustCoeff() = makeCommand(CMD_READ_ADJUST_COEFF)
    fun makeSetFilterClose() = makeCommand(CMD_FILTER_0_5HZ, byteArrayOf(1))

    fun makeSetTime(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return makeCommand(CMD_SET_TIME, byteArrayOf(
            (cal.get(java.util.Calendar.YEAR) - 2000).toByte(),
            (cal.get(java.util.Calendar.MONTH) + 1).toByte(),
            cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            cal.get(java.util.Calendar.MINUTE).toByte(),
            cal.get(java.util.Calendar.SECOND).toByte(),
        ))
    }

    fun rebuildEcg(high: Int, low: Int): Int =
        (((high and 0x1F) shl 7) or (low and 0x7F)) - ECG_BASELINE

    fun checkLeadOff(data: ByteArray, offset: Int, numLeads: Int): Boolean {
        val base = offset + 1
        for (i in 0 until numLeads) {
            if ((data[base + i * 2 + 1].toInt() shr 5) and 1 == 1) return true
        }
        return false
    }

    fun verifyChecksum(data: ByteArray, offset: Int): Boolean {
        val length = data[offset].toInt() and 0xFF
        if (offset + length >= data.size) return false
        val expected = data[offset + length]
        var s = 0
        for (i in 0 until length) {
            val b = data[offset + i].toInt() and 0xFF
            if (b == 0xFF) return false
            s = (s + b) and 0xFF
        }
        if (s == 0xFF) s = 0xFE
        return s.toByte() == expected
    }

    fun decodeEcg(data: ByteArray, offset: Int, numLeads: Int): IntArray? {
        val length = data[offset].toInt() and 0xFF
        if (length != numLeads * 2 + 2) return null
        if (!verifyChecksum(data, offset)) return null
        val samples = IntArray(numLeads)
        val base = offset + 1
        for (i in 0 until numLeads) {
            val pos = base + i * 2
            samples[i] = rebuildEcg(
                data[pos + 1].toInt() and 0xFF,
                data[pos + 2].toInt() and 0xFF
            )
        }
        return samples
    }

    fun decodeVersion(data: ByteArray, offset: Int): String? {
        val length = data[offset].toInt() and 0xFF
        val numChars = length - 2
        if (numChars <= 0) return null
        return String(ByteArray(numChars) { data[offset + 2 + it] }, Charsets.ISO_8859_1)
    }

    fun decodeAnswer(data: ByteArray, offset: Int): Int? {
        val length = data[offset].toInt() and 0xFF
        if (length < 3) return null
        return data[offset + 2].toInt() and 0xFF
    }
}

/**
 * Reassembles byte stream into complete protocol packets.
 *
 * Buffer is an ArrayDeque so head removal is O(1) — at 200 Hz with ~7 bytes
 * per packet the previous mutableListOf<Byte>.removeAt(0) implementation
 * was O(n²) per second of data, which became measurable on long Holter
 * recordings.
 */
class StreamParser {
    private val buffer = ArrayDeque<Byte>()

    data class Packet(val type: Int, val raw: ByteArray, val offset: Int)

    fun reset() { buffer.clear() }

    fun feed(data: ByteArray, length: Int = data.size): List<Packet> {
        for (i in 0 until length) buffer.addLast(data[i])
        val packets = mutableListOf<Packet>()

        while (buffer.size >= 4) {
            val hdrIdx = buffer.indexOf(Protocol.HEADER)
            if (hdrIdx < 0) { buffer.clear(); break }
            if (hdrIdx > 0) { repeat(hdrIdx) { buffer.removeFirst() } }
            if (buffer.size < 4) break

            val pktLen = buffer[1].toInt() and 0xFF
            if (pktLen == 0 || pktLen == 0xFF) { buffer.removeFirst(); continue }

            val total = pktLen + 2
            if (buffer.size < total) break

            val pktType = buffer[2].toInt() and 0xFF
            val raw = ByteArray(total) { buffer[it] }
            packets.add(Packet(pktType, raw, 1))
            repeat(total) { buffer.removeFirst() }
        }
        return packets
    }
}
