package bd.callbridge.audio

import kotlin.math.sin

/**
 * M1b acceptance signal generator (spec §7: "Success = a 1 kHz tone from the app is audible on
 * the far phone"). Pure math, no Android dependencies, so it's fully JVM-unit-testable.
 */
object TestTone {
    const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
    const val DEFAULT_FREQUENCY_HZ: Double = 1000.0
    const val DEFAULT_DURATION_MS: Int = 2_000

    /** Generates [durationMs] of a [frequencyHz] sine wave as mono PCM16 samples at
     *  [sampleRateHz]. [amplitude] is a fraction of full scale (0..1] — kept below 1.0 to avoid
     *  clipping once routed through a stereo-duplicating injector or a lossy telephony codec. */
    fun generate(
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        durationMs: Int = DEFAULT_DURATION_MS,
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        amplitude: Double = 0.6,
    ): ShortArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(durationMs > 0) { "durationMs must be positive" }
        require(amplitude in 0.0..1.0) { "amplitude must be in [0, 1]" }

        val sampleCount = sampleRateHz.toLong() * durationMs / 1000L
        val out = ShortArray(sampleCount.toInt())
        val angularFrequency = 2.0 * Math.PI * frequencyHz / sampleRateHz
        for (i in out.indices) {
            val sampleValue = amplitude * Short.MAX_VALUE * sin(angularFrequency * i)
            out[i] = sampleValue.toInt().toShort()
        }
        return out
    }
}
