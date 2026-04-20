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
        private const val INIT_RR = 300
        private const val LP_LEN = 10
        private const val HP_LEN = 25
        private const val DERIV_LEN = 2
        private const val MVWINT_LEN = 16
        private const val SAMPLE_RATE = 200
        private const val REFRACTORY = 30
        private const val WARMUP = 100
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

    private val rrBuf = IntArray(8) { INIT_RR }
    private var rrIdx = 0
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

            rrBuf[rrIdx] = rr
            rrIdx = (rrIdx + 1) % 8

            beatCount++
            if (beatCount > 1) {
                val meanRr = rrBuf.sum() / 8.0
                if (meanRr > 0) {
                    hr = (60.0 * SAMPLE_RATE / meanRr).toInt()
                }
            }
            lastHr = hr
        } else if (peak > 0) {
            noiseLevel = (noiseLevel + ALPHA * (peak - noiseLevel)).toInt()
        }

        threshold = noiseLevel + (signalLevel - noiseLevel) / 4

        return hr
    }
}
