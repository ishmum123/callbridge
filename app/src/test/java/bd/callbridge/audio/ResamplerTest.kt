package bd.callbridge.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {

    private fun sine(rate: Int, freqHz: Double, seconds: Double, amplitude: Double = 8000.0): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i -> (amplitude * sin(2.0 * PI * freqHz * i / rate)).toInt().toShort() }
    }

    /**
     * Phase-independent THD+N-style SNR: fundamental-bin energy vs. everything else (harmonics,
     * aliasing, resampling noise), measured via a direct DFT over a window spanning an exact
     * integer number of [freqHz] cycles at [outRate] — no lag search / correlation alignment
     * needed, since a magnitude-only bin comparison doesn't care about the signal's phase.
     *
     * [freqHz] and [outRate] must divide evenly (true for all frequencies/rates used below) so
     * the window length is an exact integer number of samples and the fundamental lands exactly
     * on bin [cycles] with zero spectral leakage.
     */
    private fun snrDb(actual: ShortArray, outRate: Int, freqHz: Double, skip: Int = 200, cycles: Int = 100): Double {
        val periodSamples = outRate / freqHz
        require(periodSamples == periodSamples.roundToInt().toDouble()) {
            "freqHz=$freqHz must evenly divide outRate=$outRate for an integer-cycle DFT window"
        }
        val n = periodSamples.roundToInt() * cycles
        require(actual.size >= skip + n) {
            "not enough samples for a $cycles-cycle window: need ${skip + n}, got ${actual.size}"
        }
        val window = actual.copyOfRange(skip, skip + n)

        var totalEnergy = 0.0
        for (v in window) totalEnergy += v.toDouble() * v.toDouble()
        val totalEnergyFreqDomain = n.toDouble() * totalEnergy // Parseval: sum|X[k]|^2 = n * sum x[i]^2

        val k = cycles // exact fundamental bin: freqHz * n / outRate == cycles
        var re = 0.0
        var im = 0.0
        for (i in window.indices) {
            val angle = 2.0 * PI * k * i / n
            re += window[i] * cos(angle)
            im -= window[i] * sin(angle)
        }
        // Real signal: energy at bin k mirrors bin (n-k); both count as "fundamental".
        val fundamentalEnergy = 2.0 * (re * re + im * im)
        val noiseEnergy = (totalEnergyFreqDomain - fundamentalEnergy).coerceAtLeast(1e-9)
        return 10.0 * log10(fundamentalEnergy / noiseEnergy)
    }

    @Test
    fun `8kHz to 16kHz sine has low distortion and correct length ratio`() {
        val input = sine(8000, 1000.0, seconds = 0.5)
        val resampler = Resampler(8000, 16000)
        val output = resampler.process(input)

        val expectedLen = input.size * 2
        assertTrue(
            "expected ~$expectedLen samples, got ${output.size}",
            abs(output.size - expectedLen) <= 70,
        )

        val snr = snrDb(output, 16000, 1000.0)
        assertTrue("SNR too low: $snr dB", snr > 60.0)
    }

    @Test
    fun `24kHz to 8kHz sine has low distortion and correct length ratio`() {
        val input = sine(24000, 1000.0, seconds = 0.5)
        val resampler = Resampler(24000, 8000)
        val output = resampler.process(input)

        val expectedLen = input.size / 3
        assertTrue(
            "expected ~$expectedLen samples, got ${output.size}",
            abs(output.size - expectedLen) <= 70,
        )

        val snr = snrDb(output, 8000, 1000.0)
        assertTrue("SNR too low: $snr dB", snr > 60.0)
    }

    @Test
    fun `24kHz to 16kHz sine has low distortion and correct length ratio`() {
        val input = sine(24000, 1000.0, seconds = 0.5)
        val resampler = Resampler(24000, 16000)
        val output = resampler.process(input)

        val expectedLen = input.size * 2 / 3
        assertTrue(
            "expected ~$expectedLen samples, got ${output.size}",
            abs(output.size - expectedLen) <= 70,
        )

        val snr = snrDb(output, 16000, 1000.0)
        assertTrue("SNR too low: $snr dB", snr > 60.0)
    }

    @Test
    fun `streaming in small irregular chunks matches one big call (no clicks at boundaries)`() {
        val input = sine(8000, 1000.0, seconds = 0.5)

        val wholeOutput = Resampler(8000, 16000).let { it.process(input) + it.process(ShortArray(0)) }

        val chunked = Resampler(8000, 16000)
        val chunkedOutput = ArrayList<Short>()
        var offset = 0
        val chunkSizes = intArrayOf(37, 500, 213, 1, 900, 64, 128, 777)
        var sizeIdx = 0
        while (offset < input.size) {
            val size = chunkSizes[sizeIdx % chunkSizes.size].coerceAtMost(input.size - offset)
            sizeIdx++
            val piece = input.copyOfRange(offset, offset + size)
            offset += size
            chunked.process(piece).forEach { chunkedOutput.add(it) }
        }
        chunked.process(ShortArray(0)).forEach { chunkedOutput.add(it) }

        val minLen = minOf(wholeOutput.size, chunkedOutput.size)
        assertTrue("outputs should be nearly the same length", abs(wholeOutput.size - chunkedOutput.size) <= 2)

        var maxDiff = 0
        for (i in 0 until minLen) {
            val diff = abs(wholeOutput[i] - chunkedOutput[i])
            if (diff > maxDiff) maxDiff = diff
        }
        assertTrue("chunked vs single-call output diverged too much: maxDiff=$maxDiff", maxDiff <= 2)
    }

    @Test
    fun `monoToStereo interleaves left and right identically`() {
        val mono = shortArrayOf(1, 2, 3, -4)
        val stereo = Resampler.monoToStereo(mono)
        assertEquals(8, stereo.size)
        assertEquals(shortArrayOf(1, 1, 2, 2, 3, 3, -4, -4).toList(), stereo.toList())
    }

    @Test
    fun `1kHz round trip 16kHz to 24kHz back to 16kHz stays low distortion`() {
        // Sanity check chained resamplers behave (used conceptually by capture->gemini->inject path).
        val input = sine(16000, 1000.0, seconds = 0.3)
        val up = Resampler(16000, 24000).let { it.process(input) + it.process(ShortArray(0)) }
        val down = Resampler(24000, 16000).let { it.process(up) + it.process(ShortArray(0)) }

        val snr = snrDb(down, 16000, 1000.0)
        assertTrue("round-trip SNR too low: $snr dB", snr > 50.0)
    }
}
