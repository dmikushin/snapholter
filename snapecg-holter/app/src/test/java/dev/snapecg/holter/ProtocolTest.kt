package dev.snapecg.holter

import dev.snapecg.holter.bluetooth.Protocol
import dev.snapecg.holter.bluetooth.StreamParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SnapECG protocol — no Android context needed.
 * Cross-validated against Python snapecg.protocol test cases.
 */
class ProtocolTest {

    @Test
    fun `checksum matches Python reference`() {
        // From Python: checksum(b'\x03\x12\x00') == 0x15
        val data1 = byteArrayOf(0x03, 0x12, 0x00)
        assertEquals(0x15.toByte(), Protocol.checksum(data1))

        // From Python: checksum(b'\x03\x10\x01') == 0x14
        val data2 = byteArrayOf(0x03, 0x10, 0x01)
        assertEquals(0x14.toByte(), Protocol.checksum(data2))
    }

    @Test
    fun `checksum 0xFF becomes 0xFE`() {
        // Find input that sums to 0xFF and verify it becomes 0xFE
        // 0xFF = 255. e.g. [0x80, 0x7F] = 128+127 = 255
        val data = byteArrayOf(0x80.toByte(), 0x7F)
        assertEquals(0xFE.toByte(), Protocol.checksum(data))
    }

    @Test
    fun `makeStart produces correct packet`() {
        val pkt = Protocol.makeStart()
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x03, 0x10, 0x01, 0x14),
            pkt
        )
    }

    @Test
    fun `makeStop produces correct packet`() {
        val pkt = Protocol.makeStop()
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x03, 0x10, 0x00, 0x13),
            pkt
        )
    }

    @Test
    fun `makeGetVersion produces correct packet`() {
        val pkt = Protocol.makeGetVersion()
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x03, 0x12, 0x00, 0x15),
            pkt
        )
    }

    @Test
    fun `rebuildEcg baseline is zero`() {
        // high=0x10, low=0x00: ((0x10 & 0x1F) << 7) | 0 - 2048 = 2048 - 2048 = 0
        assertEquals(0, Protocol.rebuildEcg(0x10, 0x00))
    }

    @Test
    fun `rebuildEcg positive value`() {
        // high=0x11, low=0x00: ((0x11 & 0x1F) << 7) | 0 - 2048 = 2176 - 2048 = 128
        assertEquals(128, Protocol.rebuildEcg(0x11, 0x00))
    }

    @Test
    fun `rebuildEcg negative value`() {
        // high=0x0F, low=0x00: ((0x0F & 0x1F) << 7) | 0 - 2048 = 1920 - 2048 = -128
        assertEquals(-128, Protocol.rebuildEcg(0x0F, 0x00))
    }

    @Test
    fun `rebuildEcg with low bits`() {
        // high=0x10, low=0x01: ((0x10 & 0x1F) << 7) | 1 - 2048 = 2049 - 2048 = 1
        assertEquals(1, Protocol.rebuildEcg(0x10, 0x01))
    }

    @Test
    fun `verifyChecksum valid packet`() {
        // ECG_1 packet: FF 04 00 10 00 14
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        assertTrue(Protocol.verifyChecksum(pkt, 1))
    }

    @Test
    fun `verifyChecksum invalid packet`() {
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x99.toByte())
        assertFalse(Protocol.verifyChecksum(pkt, 1))
    }

    @Test
    fun `decodeEcg single lead`() {
        // FF 04 00 10 00 14 → ECG_1, sample = rebuildEcg(0x10, 0x00) = 0
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        val samples = Protocol.decodeEcg(pkt, 1, 1)
        assertNotNull(samples)
        assertEquals(1, samples!!.size)
        assertEquals(0, samples[0])
    }

    @Test
    fun `decodeEcg wrong length returns null`() {
        // Length field says 4 but only 1 lead needs 4 → ok. Try with 2 leads → null
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        assertNull(Protocol.decodeEcg(pkt, 1, 2))
    }

    @Test
    fun `decodeVersion extracts string`() {
        // Simulate version response: FF 0B 12 45 2d 42 31 30 56 31 2e 35 checksum
        // "E-B10V1.5"
        val payload = byteArrayOf(
            0x0B, 0x12,
            0x45, 0x2d, 0x42, 0x31, 0x30, 0x56, 0x31, 0x2e, 0x35
        )
        // Need proper checksum
        val cs = Protocol.checksum(payload)
        val pkt = byteArrayOf(0xFF.toByte()) + payload + byteArrayOf(cs)

        val version = Protocol.decodeVersion(pkt, 1)
        assertEquals("E-B10V1.5", version)
    }

    @Test
    fun `decodeAnswer extracts code`() {
        // Answer OK: FF 03 1F 00 checksum
        val payload = byteArrayOf(0x03, 0x1F, 0x00)
        val cs = Protocol.checksum(payload)
        val pkt = byteArrayOf(0xFF.toByte()) + payload + byteArrayOf(cs)

        assertEquals(0, Protocol.decodeAnswer(pkt, 1))
    }

    @Test
    fun `checkLeadOff detects lead off`() {
        // Lead off when bit 5 of high byte is set: high = 0x30 (bit 5 = 1)
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x30, 0x00, 0x00)
        assertTrue(Protocol.checkLeadOff(pkt, 1, 1))
    }

    @Test
    fun `checkLeadOff normal contact`() {
        // Normal: high = 0x10 (bit 5 = 0)
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x00)
        assertFalse(Protocol.checkLeadOff(pkt, 1, 1))
    }
}

class StreamParserTest {

    @Test
    fun `parse complete packet`() {
        val parser = StreamParser()
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        val result = parser.feed(pkt)
        assertEquals(1, result.size)
        assertEquals(0, result[0].type) // ECG_1
    }

    @Test
    fun `parse multiple packets in one feed`() {
        val parser = StreamParser()
        val pkt1 = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        val pkt2 = byteArrayOf(0xFF.toByte(), 0x03, 0x1F, 0x00, 0x22)
        val result = parser.feed(pkt1 + pkt2)
        assertEquals(2, result.size)
        assertEquals(0, result[0].type)   // ECG_1
        assertEquals(0x1F, result[1].type) // ANSWER
    }

    @Test
    fun `parse fragmented packet across feeds`() {
        val parser = StreamParser()
        val full = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)

        // Feed first 3 bytes
        val r1 = parser.feed(full.sliceArray(0..2))
        assertEquals(0, r1.size) // incomplete

        // Feed remaining 3 bytes
        val r2 = parser.feed(full.sliceArray(3..5))
        assertEquals(1, r2.size)
        assertEquals(0, r2[0].type)
    }

    @Test
    fun `skip garbage before header`() {
        val parser = StreamParser()
        val garbage = byteArrayOf(0x00, 0x42, 0x99.toByte())
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        val result = parser.feed(garbage + pkt)
        assertEquals(1, result.size)
        assertEquals(0, result[0].type)
    }

    @Test
    fun `skip invalid length 0xFF`() {
        val parser = StreamParser()
        // Invalid: length = 0xFF
        val bad = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x10, 0x00, 0x14)
        // Valid packet follows
        val good = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        val result = parser.feed(bad + good)
        // Should skip the bad one and find the good one
        assertEquals(1, result.size)
    }

    @Test
    fun `empty feed returns empty`() {
        val parser = StreamParser()
        assertEquals(0, parser.feed(byteArrayOf()).size)
    }

    @Test
    fun `continuous stream of ECG packets`() {
        val parser = StreamParser()
        val pkt = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x10, 0x00, 0x14)
        // 100 packets
        val stream = ByteArray(pkt.size * 100)
        for (i in 0 until 100) pkt.copyInto(stream, i * pkt.size)

        val result = parser.feed(stream)
        assertEquals(100, result.size)
    }
}

class CrossValidationTest {

    /**
     * Cross-validate: same raw bytes must produce same decoded values
     * in both Kotlin and Python implementations.
     *
     * These are actual packets captured from a real SnapECG B10 device.
     */
    @Test
    fun `real device version response matches Python decode`() {
        // Actual captured: ff 0b 12 45 2d 42 31 30 56 31 2e 35 1c
        // Python decodes to: "E-B10V1.5"
        val raw = byteArrayOf(
            0xFF.toByte(), 0x0B, 0x12,
            0x45, 0x2d, 0x42, 0x31, 0x30, 0x56, 0x31, 0x2e, 0x35,
            0x1c
        )
        val version = Protocol.decodeVersion(raw, 1)
        assertEquals("E-B10V1.5", version)
    }

    @Test
    fun `real device ECG baseline packet`() {
        // Actual: ff 04 00 30 00 34 (lead-off baseline signal)
        // Python: rebuildEcg(0x30, 0x00) = ((0x30 & 0x1F) << 7) | 0 - 2048 = 2048 - 2048 = 0
        // But 0x30 has bit 5 set → lead off
        val raw = byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x30, 0x00, 0x34)
        val samples = Protocol.decodeEcg(raw, 1, 1)
        assertNotNull(samples)
        assertEquals(0, samples!![0]) // baseline = 0
        assertTrue(Protocol.checkLeadOff(raw, 1, 1)) // lead off detected
    }

    @Test
    fun `real device answer OK`() {
        // Actual: ff 03 1f 00 22
        val raw = byteArrayOf(0xFF.toByte(), 0x03, 0x1F, 0x00, 0x22)
        assertEquals(0, Protocol.decodeAnswer(raw, 1)) // 0 = OK
    }

    @Test
    fun `makeSetTime packet format valid`() {
        val pkt = Protocol.makeSetTime()
        // Should be: FF [len=08] [cmd=16] [yr] [mo] [day] [hr] [min] [sec] [checksum]
        assertEquals(0xFF.toByte(), pkt[0])
        assertEquals(0x08.toByte(), pkt[1]) // 6 payload + 2
        assertEquals(0x16.toByte(), pkt[2]) // CMD_SET_TIME
        assertEquals(10, pkt.size)
        // Verify checksum
        assertTrue(Protocol.verifyChecksum(pkt, 1))
    }
}
