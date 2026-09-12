package bd.callbridge.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fixed-ratio, stateful, pure-Kotlin resampler using windowed-sinc (Hann-windowed) band-limited
 * interpolation. Used for 8 kHz capture -> 16 kHz (spec §4.2) and 24 kHz Gemini output ->
 * 8/16 kHz injector input (spec §4.4).
 *
 * Stateful across [process] calls: the fractional read position and a small tail of
 * not-yet-fully-consumed input samples carry over between calls, so streaming the same audio
 * through many small chunks produces bit-identical output to a single big call — no clicks or
 * discontinuities at chunk boundaries.
 *
 * The resample ratio `inRate/outRate` is rational, so the fractional read position cycles
 * through a fixed, small set of phases (period = `outRate / gcd(inRate, outRate)`); the sinc/Hann
 * kernel for each phase is precomputed once in [phaseCoeffs] rather than re-evaluated with
 * `sin`/`cos` per output sample per tap. For pathological rate pairs whose reduced period would
 * be too large to table cheaply, this falls back to evaluating the kernel per-sample instead
 * (correct either way, just slower).
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val halfWidth: Int = 32) {

    init {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        require(halfWidth > 0) { "halfWidth must be positive" }
    }

    /** Step, in input samples, advanced per output sample. */
    private val step: Double = inRate.toDouble() / outRate.toDouble()

    /** Low-pass cutoff (cycles/input-sample). When upsampling (outRate >= inRate) this is just
     *  the input Nyquist — no attenuation needed, nothing above it exists to alias. When
     *  downsampling it's the output Nyquist backed off 10% for the filter's transition band, to
     *  avoid aliasing. */
    private val cutoff: Double =
        if (outRate < inRate) 0.5 * (outRate.toDouble() / inRate.toDouble()) * 0.9 else 0.5

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun hann(d: Double): Double {
        val t = d / halfWidth
        return if (t <= -1.0 || t >= 1.0) 0.0 else 0.5 * (1.0 + cos(PI * t))
    }

    private fun kernel(d: Double): Double = 2.0 * cutoff * sinc(2.0 * cutoff * d) * hann(d)

    // --- Polyphase coefficient table -----------------------------------------------------
    // The reduced fraction inRate'/outRate' (dividing both by their gcd) is the exact per-sample
    // step; over outRate' output samples the fractional position returns exactly to 0, so there
    // are only outRate' distinct phases. `numInPerCycle`/`numOutPerCycle` are that reduced pair.

    private val gcdInOut: Int = gcd(inRate, outRate)
    private val numInPerCycle: Int = inRate / gcdInOut
    private val numOutPerCycle: Int = outRate / gcdInOut

    private val usePhaseTable: Boolean = numOutPerCycle in 1..MAX_TABLE_PHASES

    /** `phaseCoeffs[phase][k + halfWidth - 1] = kernel(k - phase/numOutPerCycle)` for
     *  `k in -halfWidth+1..halfWidth`. Only populated when [usePhaseTable]. */
    private val phaseCoeffs: Array<DoubleArray>? = if (usePhaseTable) {
        Array(numOutPerCycle) { phase ->
            val frac = phase.toDouble() / numOutPerCycle
            DoubleArray(2 * halfWidth) { i -> kernel((i - halfWidth + 1) - frac) }
        }
    } else {
        null
    }

    /** Buffered input samples not yet fully consumed, as doubles. */
    private var buffer = DoubleArray(0)

    /** Integer part of the read position, in samples, relative to the start of [buffer]. */
    private var posInt = 0

    /** Fractional part of the read position, expressed as a phase in `0 until numOutPerCycle`
     *  (only meaningful/used when [usePhaseTable]). */
    private var phase = 0

    /** Fallback fractional read position (used when [usePhaseTable] is false). */
    private var posFrac = 0.0

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    /**
     * Resamples [input], returning as many output samples as can be produced from everything
     * seen so far (this call plus buffered history). Safe to call repeatedly with arbitrarily
     * sized (even empty) chunks; internal state carries over so the stream stays continuous.
     */
    fun process(input: ShortArray): ShortArray {
        if (input.isNotEmpty()) {
            val merged = DoubleArray(buffer.size + input.size)
            System.arraycopy(buffer, 0, merged, 0, buffer.size)
            for (i in input.indices) merged[buffer.size + i] = input[i].toDouble()
            buffer = merged
        }

        val estimated = (buffer.size / step).roundToInt() + 1
        var outCount = 0
        var out = ShortArray(estimated.coerceAtLeast(16))
        fun push(v: Short) {
            if (outCount == out.size) out = out.copyOf(out.size * 2 + 16)
            out[outCount++] = v
        }

        while (true) {
            val center = posInt
            if (center + halfWidth >= buffer.size) break

            var acc = 0.0
            if (usePhaseTable) {
                val coeffs = phaseCoeffs!![phase]
                for (i in coeffs.indices) {
                    val idx = center + (i - halfWidth + 1)
                    val sample = if (idx in buffer.indices) buffer[idx] else 0.0
                    acc += sample * coeffs[i]
                }
                phase += numInPerCycle
                posInt += phase / numOutPerCycle
                phase %= numOutPerCycle
            } else {
                val frac = posFrac - posInt
                for (k in -halfWidth + 1..halfWidth) {
                    val idx = center + k
                    val sample = if (idx in buffer.indices) buffer[idx] else 0.0
                    acc += sample * kernel(k - frac)
                }
                posFrac += step
                posInt = posFrac.toInt()
            }
            push(acc.roundToInt().coerceIn(-32768, 32767).toShort())
        }

        // Trim the consumed prefix, keeping enough history for future kernel taps.
        val safeToDrop = (posInt - halfWidth).coerceAtMost(buffer.size).coerceAtLeast(0)
        if (safeToDrop > 0) {
            buffer = buffer.copyOfRange(safeToDrop, buffer.size)
            posInt -= safeToDrop
            posFrac -= safeToDrop
        }
        return out.copyOf(outCount)
    }

    companion object {
        /** Above this many distinct phases, don't bother precomputing the table (memory
         *  wouldn't be worth it) — fall back to per-sample kernel evaluation instead. */
        private const val MAX_TABLE_PHASES = 4096

        /** Interleaves a mono PCM16 buffer into stereo (L=R=mono), for injector/HAL paths that
         *  require stereo input (hal-recon.md). */
        fun monoToStereo(mono: ShortArray): ShortArray {
            val out = ShortArray(mono.size * 2)
            for (i in mono.indices) {
                out[i * 2] = mono[i]
                out[i * 2 + 1] = mono[i]
            }
            return out
        }
    }
}
