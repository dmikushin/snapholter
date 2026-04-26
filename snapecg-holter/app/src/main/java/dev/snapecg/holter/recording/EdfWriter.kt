package dev.snapecg.holter.recording

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Minimal EDF+ writer for single-channel ECG with optional annotations.
 *
 * Spec: https://www.edfplus.info/specs/edf.html
 * - Signal 1: ECG, 200 samples/data record (16-bit signed)
 * - Signal 2 (optional): EDF Annotations, N bytes per record
 * - Physical dimension: uV (raw ADC units mapped 1:1)
 */
class EdfWriter(
    private val output: OutputStream,
    private val patientName: String = "",
    private val startTime: Date,
    private val sampleRate: Int = 200,
    annotations: Map<Int, String> = emptyMap(),  // data record index -> note text
) {
    companion object {
        private const val DIGITAL_MIN = -2048
        private const val DIGITAL_MAX = 2047
        private const val PHYSICAL_MIN = -2048
        private const val PHYSICAL_MAX = 2047
    }

    private val annBytesPerRecord: Int
    private val numSignals: Int
    private val headerSize: Int
    private var dataRecordCount = 0

    init {
        // Pre-compute TAL for each second that has annotations.
        // TAL format: +<onset 21 chars>\x14<duration 21 chars>\x14<text>\x14\x00
        val talBySecond = annotations.mapValues { (_, text) ->
            "+0.0".padEnd(21, ' ') + "\u0014" + "".padEnd(21, ' ') + "\u0014$text\u0014\u0000"
        }
        // Max annotation bytes across all records (round up to 4-byte boundary)
        annBytesPerRecord = (talBySecond.values.maxOfOrNull { it.length } ?: 0).let {
            (it + 3) / 4 * 4
        }
        numSignals = 1 + if (annBytesPerRecord > 0) 1 else 0
        headerSize = 256 + numSignals * 256
    }

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
        sb.append(edfStr(numSignals.toString(), 4))                 // number of signals

        // --- Signal 1: ECG (256 bytes) ---
        sb.append(edfStr("ECG", 16))
        sb.append(edfStr("AgCl electrode", 80))
        sb.append(edfStr("uV", 8))
        sb.append(edfStr(PHYSICAL_MIN.toString(), 8))
        sb.append(edfStr(PHYSICAL_MAX.toString(), 8))
        sb.append(edfStr(DIGITAL_MIN.toString(), 8))
        sb.append(edfStr(DIGITAL_MAX.toString(), 8))
        sb.append(edfStr("HP:0.05Hz LP:40Hz", 80))
        sb.append(edfStr(sampleRate.toString(), 8))
        sb.append(edfStr("", 32))

        // --- Signal 2: EDF Annotations (256 bytes, optional) ---
        if (numSignals > 1) {
            sb.append(edfStr("EDF Annotations", 16))
            sb.append(edfStr("", 80))                                // transducer
            sb.append(edfStr("", 8))                                 // physical dimension
            sb.append(edfStr("-32768", 8))                           // physical min
            sb.append(edfStr("32767", 8))                            // physical max
            sb.append(edfStr("-32768", 8))                           // digital min
            sb.append(edfStr("32767", 8))                            // digital max
            sb.append(edfStr("", 80))                                // prefiltering
            sb.append(edfStr(annBytesPerRecord.toString(), 8))       // samples per data record
            sb.append(edfStr("", 32))
        }

        val header = sb.toString()
        assert(header.length == headerSize) { "EDF header size mismatch: ${header.length} (expected $headerSize)" }
        output.write(header.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Write one data record (1 second).
     * [annotation] — TAL string for this record, or empty if no note.
     */
    fun writeDataRecord(samples: IntArray, annotation: String = "") {
        val ecgSize = samples.size * 2
        val annSize = annBytesPerRecord * 2  // each byte → 1 s16 sample
        val buf = ByteBuffer.allocate(ecgSize + annSize).order(ByteOrder.LITTLE_ENDIAN)

        // ECG samples
        for (s in samples) {
            buf.putShort(s.coerceIn(DIGITAL_MIN, DIGITAL_MAX).toShort())
        }

        // Annotation bytes (if second signal exists)
        if (numSignals > 1) {
            val annBytes = annotation.toByteArray(Charsets.UTF_8)
            for (i in 0 until annBytesPerRecord) {
                val b = if (i < annBytes.size) annBytes[i].toInt() and 0xFF else 0
                buf.putShort(b.toShort())
            }
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
