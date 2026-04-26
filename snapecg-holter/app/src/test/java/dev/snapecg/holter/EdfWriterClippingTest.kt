package dev.snapecg.holter

import dev.snapecg.holter.recording.EdfWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * Unit tests for EdfWriter clipping behaviour.
 *
 * The byte-level wire format of EDF/EDF+ is exercised end-to-end by
 * verify_edf_annotations.py at the repo root. These tests cover one
 * thing that script can't: the in-memory clippedSamples counter that
 * RecordingStore uses to surface "N samples were out of range" in the
 * status log after an export.
 */
class EdfWriterClippingTest {

    private fun newWriter(): Pair<EdfWriter, ByteArrayOutputStream> {
        val sink = ByteArrayOutputStream()
        val writer = EdfWriter(sink, patientName = "", startTime = Date())
        writer.writeHeader(totalSeconds = 1)
        return writer to sink
    }

    @Test
    fun `in-range samples do not clip`() {
        val (writer, _) = newWriter()
        // Write 200 in-range samples (one record).
        writer.writeDataRecord(IntArray(200) { (it - 100) * 10 })
        writer.close()
        assertEquals(0L, writer.clippedSamples)
    }

    @Test
    fun `samples above DIGITAL_MAX are counted`() {
        val (writer, _) = newWriter()
        // 200 samples — 50 of them way above the 2047 ceiling.
        val data = IntArray(200) { i -> if (i < 50) 5_000_000 else 0 }
        writer.writeDataRecord(data)
        writer.close()
        assertEquals(50L, writer.clippedSamples)
    }

    @Test
    fun `samples below DIGITAL_MIN are counted`() {
        val (writer, _) = newWriter()
        val data = IntArray(200) { i -> if (i % 2 == 0) -10_000 else 0 }
        writer.writeDataRecord(data)
        writer.close()
        assertEquals(100L, writer.clippedSamples)
    }

    @Test
    fun `boundary values do not clip`() {
        val (writer, _) = newWriter()
        // -2048 and 2047 are exactly at the DIGITAL_MIN/MAX edges.
        val data = IntArray(200) { i -> if (i % 2 == 0) -2048 else 2047 }
        writer.writeDataRecord(data)
        writer.close()
        assertEquals(0L, writer.clippedSamples)
    }

    @Test
    fun `count accumulates across multiple records`() {
        val sink = ByteArrayOutputStream()
        val writer = EdfWriter(sink, patientName = "", startTime = Date())
        writer.writeHeader(totalSeconds = 3)
        // 30 clipped samples per record × 3 records = 90 total.
        repeat(3) {
            val data = IntArray(200) { i -> if (i < 30) 100_000 else 0 }
            writer.writeDataRecord(data)
        }
        writer.close()
        assertEquals(90L, writer.clippedSamples)
    }
}
