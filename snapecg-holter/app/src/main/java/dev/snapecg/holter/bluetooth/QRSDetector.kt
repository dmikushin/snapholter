package dev.snapecg.holter.bluetooth

/**
 * Pan-Tompkins QRS detector — Kotlin port of snapecg.qrs.QRSDetector.
 *
 * Signal chain: (input-1024)*2 → LP → HP → deriv → |abs| → MWI → peak → threshold
 * Output: heart rate in bpm (0 if no beat detected this sample).
 */
class QRSDetector {

    companion object {
        private const val ALPHA = 0.125
        private const val INIT_THRESHOLD = 2
        private const val LP_LEN = 10
        private const val HP_LEN = 25
        private const val DERIV_LEN = 2
        private const val MVWINT_LEN = 16
        private const val SAMPLE_RATE = 200
        private const val REFRACTORY = 30
        private const val WARMUP = 100

        // Physiologically plausible RR interval bounds (samples at 200 Hz)
        // 55 samples = 218 BPM, 480 samples = 25 BPM
        private const val RR_MIN = 55
        private const val RR_MAX = 480
        private const val MIN_VALID_BEATS = 3
        private const val MAX_BEAT_GAP = 400  // 2 seconds — reset confidence if longer
    }

    private val lpBuf = IntArray(LP_LEN)
    private var lpIdx = 0
    private var lpSum = 0

    private val hpBuf = IntArray(HP_LEN)
    private var hpIdx = 0
    private var hpSum = 0

    private val derivBuf = IntArray(DERIV_LEN)
    private var derivIdx = 0

    private val mwiBuf = IntArray(MVWINT_LEN)
    private var mwiIdx = 0
    private var mwiSum = 0

    private var peakMax = 0
    private var rising = false

    private var signalLevel = 0
    private var noiseLevel = 0
    private var threshold = INIT_THRESHOLD

    private val rrBuf = IntArray(8)
    private var rrIdx = 0
    private var validBeatCount = 0
    private var samplesSinceBeat = 0

    private var beatCount = 0
    private var refractoryCount = 0
    var lastHr = 0; private set
    private var sampleIdx = 0

    private fun lpFilt(x: Int): Int {
        val oldest = lpBuf[lpIdx]
        val half = lpBuf[(lpIdx + LP_LEN / 2) % LP_LEN]
        lpSum += x - 2 * half + oldest
        lpBuf[lpIdx] = x
        lpIdx = (lpIdx + 1) % LP_LEN
        return lpSum / LP_LEN
    }

    private fun hpFilt(x: Int): Int {
        val delayed = hpBuf[(hpIdx + HP_LEN - 12) % HP_LEN]
        hpSum += x - hpBuf[hpIdx]
        val lp = hpSum / HP_LEN
        hpBuf[hpIdx] = x
        hpIdx = (hpIdx + 1) % HP_LEN
        return delayed - lp
    }

    private fun deriv(x: Int): Int {
        val old = derivBuf[derivIdx]
        derivBuf[derivIdx] = x
        derivIdx = (derivIdx + 1) % DERIV_LEN
        return x - old
    }

    private fun mvwint(x: Int): Int {
        val oldest = mwiBuf[mwiIdx]
        mwiSum += x - oldest
        mwiBuf[mwiIdx] = x
        mwiIdx = (mwiIdx + 1) % MVWINT_LEN
        return mwiSum
    }

    private fun peakDetect(x: Int): Int {
        if (x > peakMax) {
            peakMax = x
            rising = true
            return 0
        } else if (rising && x < peakMax) {
            val peak = peakMax
            peakMax = x
            rising = false
            return peak
        } else {
            peakMax = maxOf(x, peakMax - 1)
            return 0
        }
    }

    /**
     * Process one ECG sample (raw ADC, baseline ~2048).
     * @return heart rate in bpm, or 0 if no beat detected.
     */
    fun process(sample: Int): Int {
        samplesSinceBeat++
        sampleIdx++

        // Reset confidence if no beat detected for >2 seconds
        if (samplesSinceBeat > MAX_BEAT_GAP) {
            validBeatCount = 0
        }

        var x = (sample - 1024) * 2
        x = lpFilt(x)
        x = hpFilt(x)
        x = deriv(x)
        x = kotlin.math.abs(x)
        x = mvwint(x)

        val peak = peakDetect(x)

        if (refractoryCount > 0) refractoryCount--
        if (sampleIdx < WARMUP) return 0

        var hr = 0

        if (peak > 0 && peak >= threshold && refractoryCount == 0) {
            val rr = samplesSinceBeat
            samplesSinceBeat = 0
            refractoryCount = REFRACTORY

            signalLevel = (signalLevel + ALPHA * (peak - signalLevel)).toInt()

            // Only store physiologically plausible RR intervals
            if (rr in RR_MIN..RR_MAX) {
                rrBuf[rrIdx] = rr
                rrIdx = (rrIdx + 1) % 8
                validBeatCount++
            }
            // If RR is implausible (missed beat or false positive),
            // skip storing in rrBuf but keep signal tracking

            beatCount++
            if (validBeatCount >= MIN_VALID_BEATS) {
                hr = computeBpm()
            }
            // Preserve last good reading during warm-up or low-confidence
            // intervals — clearing to 0 made the UI flicker "-- bpm" between
            // beats instead of holding the last valid HR.
            if (hr > 0) lastHr = hr
        } else if (peak > 0) {
            noiseLevel = (noiseLevel + ALPHA * (peak - noiseLevel)).toInt()
        }

        threshold = noiseLevel + (signalLevel - noiseLevel) / 4

        return hr
    }

    /** Compute BPM from median of valid stored RR intervals. */
    private fun computeBpm(): Int {
        val valid = rrBuf.filter { it > 0 }.sorted()
        if (valid.size < MIN_VALID_BEATS) return 0

        val medianRr = if (valid.size % 2 == 0) {
            (valid[valid.size / 2 - 1] + valid[valid.size / 2]) / 2.0
        } else {
            valid[valid.size / 2].toDouble()
        }

        return (60.0 * SAMPLE_RATE / medianRr).toInt()
    }
}
