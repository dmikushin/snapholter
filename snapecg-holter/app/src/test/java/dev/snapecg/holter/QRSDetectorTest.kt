package dev.snapecg.holter

import dev.snapecg.holter.bluetooth.QRSDetector
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the Kotlin port of Pan-Tompkins detection. The legacy
 * verify_bpm.py exercised a Python QRSDetector that lives in a sibling
 * project (~/forge/snapecg-b10/src) — that's the *source* the Kotlin
 * was ported from, but it's not what ships in the APK. These tests
 * exercise the actual class running on every device.
 *
 * Strategy: synthesize 200 Hz baseline-centred ECG with triangular QRS
 * complexes at a known BPM, run through process(), collect the BPM
 * readings after a warm-up window, and assert the median is within
 * tolerance of the target.
 */
class QRSDetectorTest {

    companion object {
        private const val SAMPLE_RATE = 200
        private const val BASELINE = 2048
        private const val QRS_AMPLITUDE = 600
        private const val QRS_WIDTH = 20  // ~100ms at 200 Hz
        private const val WARMUP_SEC = 5.0
    }

    /** Triangular QRS at given BPM, raw ADC values (centred on BASELINE). */
    private fun generateEcg(bpm: Double, durationSec: Double = 20.0): IntArray {
        val rr = (SAMPLE_RATE * 60.0 / bpm).toInt()
        val total = (durationSec * SAMPLE_RATE).toInt()
        return IntArray(total) { i ->
            val pos = i % rr
            if (pos < QRS_WIDTH) {
                if (pos < QRS_WIDTH / 2)
                    BASELINE + QRS_AMPLITUDE * pos / (QRS_WIDTH / 2)
                else
                    BASELINE + QRS_AMPLITUDE * (QRS_WIDTH - pos) / (QRS_WIDTH / 2)
            } else BASELINE
        }
    }

    private fun generateEcgWithGap(
        bpm: Double, gapStartSec: Double, gapDurSec: Double,
        totalSec: Double = 25.0,
    ): IntArray {
        val rr = (SAMPLE_RATE * 60.0 / bpm).toInt()
        val total = (totalSec * SAMPLE_RATE).toInt()
        val gapStart = (gapStartSec * SAMPLE_RATE).toInt()
        val gapEnd = gapStart + (gapDurSec * SAMPLE_RATE).toInt()
        return IntArray(total) { i ->
            when {
                i in gapStart until gapEnd -> BASELINE  // electrode off → flat
                else -> {
                    val effective = if (i >= gapEnd) i - (gapEnd - gapStart) else i
                    val pos = effective % rr
                    if (pos < QRS_WIDTH) {
                        if (pos < QRS_WIDTH / 2)
                            BASELINE + QRS_AMPLITUDE * pos / (QRS_WIDTH / 2)
                        else
                            BASELINE + QRS_AMPLITUDE * (QRS_WIDTH - pos) / (QRS_WIDTH / 2)
                    } else BASELINE
                }
            }
        }
    }

    /** Run all samples and collect HR readings after the warm-up. */
    private fun runDetector(samples: IntArray, warmupSec: Double = WARMUP_SEC): List<Int> {
        val det = QRSDetector()
        val warmupSamples = (warmupSec * SAMPLE_RATE).toInt()
        val out = mutableListOf<Int>()
        for (i in samples.indices) {
            val hr = det.process(samples[i])
            if (i >= warmupSamples && hr > 0) out += hr
        }
        return out
    }

    private fun median(list: List<Int>): Int {
        val sorted = list.sorted()
        return sorted[sorted.size / 2]
    }

    private fun assertBpmConverges(
        samples: IntArray, expected: Int,
        tolerancePct: Int = 10,
        warmupSec: Double = WARMUP_SEC,
    ) {
        val readings = runDetector(samples, warmupSec)
        assertTrue("no HR readings produced", readings.isNotEmpty())
        // Skip first 3 readings while signal/noise envelopes settle.
        val stable = if (readings.size > 3) readings.drop(3) else readings
        val m = median(stable)
        val errPct = kotlin.math.abs(m - expected) * 100 / expected
        assertTrue(
            "median BPM=$m, expected=$expected (error=$errPct%, allowed=$tolerancePct%)",
            errPct <= tolerancePct
        )
        // Reject physiologically impossible readings post-warmup.
        for (hr in readings) {
            assertTrue("garbage HR=$hr (must be 20..250)", hr in 20..250)
        }
    }

    // ---- Clean-signal accuracy across heart-rate range ----

    @Test fun `BPM accurate at 40 (slow) bpm`() =
        assertBpmConverges(generateEcg(40.0, durationSec = 30.0), 40, warmupSec = 10.0)

    @Test fun `BPM accurate at 60 bpm`() =
        assertBpmConverges(generateEcg(60.0), 60)

    @Test fun `BPM accurate at 80 bpm`() =
        assertBpmConverges(generateEcg(80.0), 80)

    @Test fun `BPM accurate at 100 bpm`() =
        assertBpmConverges(generateEcg(100.0), 100)

    @Test fun `BPM accurate at 120 bpm`() =
        assertBpmConverges(generateEcg(120.0), 120)

    @Test fun `BPM accurate at 150 bpm`() =
        assertBpmConverges(generateEcg(150.0), 150)

    @Test fun `BPM accurate at 180 bpm`() =
        assertBpmConverges(generateEcg(180.0), 180)

    @Test fun `BPM accurate at 200 bpm (boundary RR_MIN=55)`() {
        // Tighter end of the physiological RR window — allow a bit more slack.
        assertBpmConverges(generateEcg(200.0, durationSec = 15.0), 200, tolerancePct = 15)
    }

    // ---- Gap robustness: short electrode disconnects ----

    @Test fun `3-second gap at 60 bpm produces no garbage HR`() {
        val samples = generateEcgWithGap(60.0, gapStartSec = 10.0, gapDurSec = 3.0)
        for (hr in runDetector(samples, warmupSec = 1.0)) {
            assertTrue("garbage HR=$hr during/after gap", hr in 20..250)
        }
    }

    @Test fun `3-second gap at 120 bpm produces no garbage HR`() {
        val samples = generateEcgWithGap(120.0, gapStartSec = 10.0, gapDurSec = 3.0)
        for (hr in runDetector(samples, warmupSec = 1.0)) {
            assertTrue("garbage HR=$hr during/after gap", hr in 20..250)
        }
    }

    @Test fun `5-second gap at 80 bpm resets confidence cleanly`() {
        // MAX_BEAT_GAP=400 samples=2s; a 5s gap must trigger validBeatCount=0 reset
        // and rebuild confidence after the gap without emitting garbage values.
        val samples = generateEcgWithGap(80.0, gapStartSec = 10.0, gapDurSec = 5.0)
        for (hr in runDetector(samples, warmupSec = 1.0)) {
            assertTrue("garbage HR=$hr after long gap", hr in 20..250)
        }
    }

    // ---- MIN_VALID_BEATS gate ----

    @Test fun `no HR reported until MIN_VALID_BEATS reached`() {
        // 60 BPM in the first 3 seconds gives us at most ~3 beats; the gate
        // should keep emissions quiet during the warm-up + first couple beats.
        val det = QRSDetector()
        val samples = generateEcg(60.0, durationSec = 3.0)
        val emissions = mutableListOf<Pair<Int, Int>>()
        for (i in samples.indices) {
            val hr = det.process(samples[i])
            if (hr > 0) emissions += i to hr
        }
        // Gate says: don't compute BPM until validBeatCount >= 3. So we should
        // see at most one or two HR emissions in this short window.
        assertTrue(
            "expected gate to suppress early HR; got ${emissions.size} emissions: $emissions",
            emissions.size <= 2
        )
    }
}
