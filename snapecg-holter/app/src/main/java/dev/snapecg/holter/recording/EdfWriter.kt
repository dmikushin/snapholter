package dev.snapecg.holter.recording

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Minimal EDF (European Data Format) writer for single-channel ECG.
 *
 * Spec: https://www.edfplus.info/specs/edf.html
 * - 1 signal: ECG
 * - Data records: 1 second each, 200 samples (16-bit signed)
 * - Physical dimension: uV (raw ADC units mapped linearly)
 */
class EdfWriter(
    private val output: OutputStream,
    private val patientName: String = "",
    private val startTime: Date,
    private val sampleRate: Int = 200,
) {
    companion object {
        private const val DIGITAL_MIN = -2048
        private const val DIGITAL_MAX = 2047
        private const val PHYSICAL_MIN = -2048  // uV (1:1 mapping for raw ADC)
        private const val PHYSICAL_MAX = 2047
    }

    private var dataRecordCount = 0
    private val headerSize = 256 + 256 // fixed header + 1 signal header

    fun writeHeader(totalSeconds: Int) {
        val sb = StringBuilder()

        // --- Fixed header (256 bytes) ---
        sb.append(edfStr("0", 8))                                   // version
        sb.append(edfStr(patientInfo(), 80))                        // patient
        sb.append(edfStr(recordingInfo(), 80))                      // recording
        sb.append(SimpleDateFormat("dd.MM.yy", Locale.US).format(startTime).let { edfStr(it, 8) })
        sb.append(SimpleDateFormat("HH.mm.ss", Locale.US).format(startTime).let { edfStr(it, 8) })
        sb.append(edfStr(headerSize.toString(), 8))                 // header bytes
        sb.append(edfStr("", 44))                                   // reserved
        sb.append(edfStr(totalSeconds.toString(), 8))               // num data records
        sb.append(edfStr("1", 8))                                   // data record duration (seconds)
        sb.append(edfStr("1", 4))                                   // number of signals

        // --- Signal header (256 bytes for 1 signal) ---
        sb.append(edfStr("ECG", 16))                                // label
        sb.append(edfStr("AgCl electrode", 80))                     // transducer
        sb.append(edfStr("uV", 8))                                  // physical dimension
        sb.append(edfStr(PHYSICAL_MIN.toString(), 8))               // physical min
        sb.append(edfStr(PHYSICAL_MAX.toString(), 8))               // physical max
        sb.append(edfStr(DIGITAL_MIN.toString(), 8))                // digital min
        sb.append(edfStr(DIGITAL_MAX.toString(), 8))                // digital max
        sb.append(edfStr("HP:0.05Hz LP:40Hz", 80))                 // prefiltering
        sb.append(edfStr(sampleRate.toString(), 8))                 // samples per data record
        sb.append(edfStr("", 32))                                   // reserved

        val header = sb.toString()
        assert(header.length == headerSize) { "EDF header size mismatch: ${header.length}" }
        output.write(header.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Write one data record (1 second = sampleRate samples).
     * Samples should be raw ADC values (baseline-subtracted + 2048 restored not needed,
     * we write as-is since digital range matches).
     */
    fun writeDataRecord(samples: IntArray) {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            buf.putShort(s.coerceIn(DIGITAL_MIN, DIGITAL_MAX).toShort())
        }
        output.write(buf.array())
        dataRecordCount++
    }

    fun close() {
        output.flush()
        output.close()
    }

    private fun patientInfo(): String {
        return if (patientName.isNotBlank()) "X X X $patientName" else "X X X X"
    }

    private fun recordingInfo(): String {
        val dateStr = SimpleDateFormat("dd-MMM-yyyy", Locale.US).format(startTime).uppercase()
        return "Startdate $dateStr X SnapECG_B10 snapecg-holter"
    }

    private fun edfStr(value: String, length: Int): String {
        return value.take(length).padEnd(length, ' ')
    }
}
