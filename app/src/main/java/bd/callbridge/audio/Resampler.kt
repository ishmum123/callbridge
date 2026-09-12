package bd.callbridge.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
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
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val halfWidth: Int = 32) {

    init {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        require(halfWidth > 0) { "halfWidth must be positive" }
    }

    /** Step, in input samples, advanced per output sample. */
    private val step: Double = inRate.toDouble() / outRate.toDouble()

    /** Low-pass cutoff (cycles/input-sample), backed off 10% from Nyquist for the transition
     *  band. When upsampling this is just the input Nyquist (no attenuation needed); when
     *  downsampling it's the output Nyquist, to avoid aliasing. */
    private val cutoff: Double = 0.5 * minOf(1.0, outRate.toDouble() / inRate.toDouble()) * 0.9

    /** Buffered input samples not yet fully consumed, as doubles. */
    private var buffer = DoubleArray(0)

    /** Read position, in samples, relative to the start of [buffer]. */
    private var pos = 0.0

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun hann(d: Double): Double {
        val t = d / halfWidth
        return if (t <= -1.0 || t >= 1.0) 0.0 else 0.5 * (1.0 + cos(PI * t))
    }

    private fun kernel(d: Double): Double = 2.0 * cutoff * sinc(2.0 * cutoff * d) * hann(d)

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
        val outputs = ArrayList<Short>(estimated.coerceAtLeast(0))
        while (true) {
            val center = floor(pos).toInt()
            // Require full lookahead support (center + halfWidth) before producing this sample.
            if (center + halfWidth >= buffer.size) break
            val frac = pos - center
            var acc = 0.0
            for (k in -halfWidth + 1..halfWidth) {
                val idx = center + k
                val sample = if (idx in buffer.indices) buffer[idx] else 0.0
                acc += sample * kernel(k - frac)
            }
            outputs.add(acc.roundToInt().coerceIn(-32768, 32767).toShort())
            pos += step
        }

        // Trim the consumed prefix, keeping enough history for future kernel taps.
        val safeToDrop = (floor(pos).toInt() - halfWidth).coerceAtMost(buffer.size).coerceAtLeast(0)
        if (safeToDrop > 0) {
            buffer = buffer.copyOfRange(safeToDrop, buffer.size)
            pos -= safeToDrop
        }
        return outputs.toShortArray()
    }

    companion object {
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
