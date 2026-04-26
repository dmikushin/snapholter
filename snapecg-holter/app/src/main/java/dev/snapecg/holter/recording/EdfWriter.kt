package dev.snapecg.holter.recording

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Minimal EDF+ writer for single-channel ECG with optional annotations.
 *
 * Spec: https://www.edfplus.info/specs/edfplus.html
 * - Signal 1: ECG, sampleRate samples/data record (16-bit signed)
 * - Signal 2 (optional): EDF Annotations, Ns int16 = 2*Ns bytes per record
 * - Physical dimension: uV (raw ADC units mapped 1:1)
 *
 * EDF+ TAL format (per spec section 2.2):
 *   Onset[\x15Duration]\x14Annotation\x14\x00
 *
 * Each data record begins with a "time-keeping" TAL "+<n>\x14\x14\x00" that
 * names the record's start time in seconds since file start. User annotations
 * follow as additional TALs in the same record.
 */
class EdfWriter(
    private val output: OutputStream,
    private val patientName: String = "",
    private val startTime: Date,
    private val sampleRate: Int = 200,
    private val annotations: Map<Int, String> = emptyMap(),  // record index -> note text
) {
    companion object {
        private const val DIGITAL_MIN = -2048
        private const val DIGITAL_MAX = 2047
        private const val PHYSICAL_MIN = -2048
        private const val PHYSICAL_MAX = 2047
        // "+<n>\x14\x14\x00" — 24 bytes covers any plausible record index.
        private const val TIMEKEEPING_MAX_BYTES = 24
    }

    private val hasAnnotations = annotations.isNotEmpty()
    private val annSamplesPerRecord: Int  // Ns: file space per record = 2*Ns bytes
    private val numSignals: Int = if (hasAnnotations) 2 else 1
    private val headerSize: Int = 256 + numSignals * 256
    private var dataRecordCount = 0

    /**
     * Number of ECG samples clipped to the `[DIGITAL_MIN, DIGITAL_MAX]`
     * range over the lifetime of this writer. EDF stores 12-bit signed
     * values, so any incoming integer outside that range is silently
     * squashed; tracking the count lets the caller surface "N samples
     * were out of range" in the export summary so a downstream
     * cardiologist isn't blindsided by silent data loss.
     */
    var clippedSamples: Long = 0L
        private set

    init {
        annSamplesPerRecord = if (hasAnnotations) {
            val maxUserTalBytes = annotations.entries.maxOf { (recordIdx, text) ->
                userTal(recordIdx, text).toByteArray(Charsets.UTF_8).size
            }
            // Each record carries timekeeping + (optional) user TAL + zero padding
            val totalMaxBytes = TIMEKEEPING_MAX_BYTES + maxUserTalBytes
            (totalMaxBytes + 1) / 2  // round up to int16 boundary
        } else 0
    }

    private fun timeKeepingTal(recordIdx: Int): String =
        "+$recordIdx\u0014\u0014\u0000"

    private fun userTal(recordIdx: Int, text: String): String =
        "+$recordIdx\u0014$text\u0014\u0000"

    fun writeHeader(totalSeconds: Int) {
        val sb = StringBuilder()

        // --- Fixed header (256 bytes) ---
        sb.append(edfStr("0", 8))                                   // version
        sb.append(edfStr(patientInfo(), 80))                        // patient
        sb.append(edfStr(recordingInfo(), 80))                      // recording
        sb.append(SimpleDateFormat("dd.MM.yy", Locale.US).format(startTime).let { edfStr(it, 8) })
        sb.append(SimpleDateFormat("HH.mm.ss", Locale.US).format(startTime).let { edfStr(it, 8) })
        sb.append(edfStr(headerSize.toString(), 8))                 // header bytes
        // EDF+ continuous marker in reserved field (per spec section 2.1)
        sb.append(edfStr(if (hasAnnotations) "EDF+C" else "", 44))
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
        if (hasAnnotations) {
            sb.append(edfStr("EDF Annotations", 16))
            sb.append(edfStr("", 80))                                // transducer
            sb.append(edfStr("", 8))                                 // physical dimension
            sb.append(edfStr("-32768", 8))                           // physical min
            sb.append(edfStr("32767", 8))                            // physical max
            sb.append(edfStr("-32768", 8))                           // digital min
            sb.append(edfStr("32767", 8))                            // digital max
            sb.append(edfStr("", 80))                                // prefiltering
            sb.append(edfStr(annSamplesPerRecord.toString(), 8))     // samples per data record
            sb.append(edfStr("", 32))
        }

        val header = sb.toString()
        check(header.length == headerSize) {
            "EDF header size mismatch: ${header.length} (expected $headerSize)"
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Write one data record (1 second). The annotation block is built from the
     * `annotations` map passed at construction; record index is tracked internally.
     *
     * Any sample outside the `[DIGITAL_MIN, DIGITAL_MAX]` range is silently
     * clipped — that is part of how EDF stores 12-bit signed ECG, but it
     * can also mask data corruption (e.g. a stray 0xDEADBEEF from a BT
     * glitch). [clippedSamples] counts every clip across the whole writer
     * lifetime so callers can audit the export.
     */
    fun writeDataRecord(samples: IntArray) {
        val ecgBytes = samples.size * 2
        val annBytes = annSamplesPerRecord * 2
        val buf = ByteBuffer.allocate(ecgBytes + annBytes).order(ByteOrder.LITTLE_ENDIAN)

        // ECG samples (int16 LE)
        for (s in samples) {
            val clamped = s.coerceIn(DIGITAL_MIN, DIGITAL_MAX)
            if (clamped != s) clippedSamples++
            buf.putShort(clamped.toShort())
        }

        // EDF Annotations channel: TAL bytes packed directly into 2*Ns byte block.
        // Required by EDF+: every record starts with a time-keeping TAL.
        if (hasAnnotations) {
            val timeKeeping = timeKeepingTal(dataRecordCount)
            val userPart = annotations[dataRecordCount]
                ?.let { userTal(dataRecordCount, it) }
                ?: ""
            val talBytes = (timeKeeping + userPart).toByteArray(Charsets.UTF_8)
            check(talBytes.size <= annBytes) {
                "TAL overflow at record $dataRecordCount: ${talBytes.size} > $annBytes"
            }
            buf.put(talBytes)
            // Zero-pad remaining bytes
            repeat(annBytes - talBytes.size) { buf.put(0.toByte()) }
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
