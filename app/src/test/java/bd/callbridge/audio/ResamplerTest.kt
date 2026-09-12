package bd.callbridge.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {

    private fun sine(rate: Int, freqHz: Double, seconds: Double, amplitude: Double = 8000.0): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i -> (amplitude * sin(2.0 * PI * freqHz * i / rate)).toInt().toShort() }
    }

    /** Best-lag cross-correlation alignment, then SNR of (aligned expected - actual). */
    private fun snrDb(actual: ShortArray, expectedRate: Int, freqHz: Double, amplitude: Double, maxLagSearch: Int = 80): Double {
        // Skip the filter's warm-up/settle region at both ends.
        val skip = 100
        val core = actual.copyOfRange(skip, actual.size - skip)

        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -maxLagSearch..maxLagSearch) {
            var score = 0.0
            for (i in core.indices) {
                val t = i + skip + lag
                val expected = amplitude * sin(2.0 * PI * freqHz * t / expectedRate)
                score += core[i] * expected
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        var signalEnergy = 0.0
        var noiseEnergy = 0.0
        for (i in core.indices) {
            val t = i + skip + bestLag
            val expected = amplitude * sin(2.0 * PI * freqHz * t / expectedRate)
            val err = core[i] - expected
            signalEnergy += expected * expected
            noiseEnergy += err * err
        }
        return 10.0 * log10(signalEnergy / noiseEnergy.coerceAtLeast(1e-9))
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

        val snr = snrDb(output, 16000, 1000.0, 8000.0)
        assertTrue("SNR too low: $snr dB", snr > 30.0)
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

        val snr = snrDb(output, 8000, 1000.0, 8000.0)
        assertTrue("SNR too low: $snr dB", snr > 25.0)
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

        val snr = snrDb(output, 16000, 1000.0, 8000.0)
        assertTrue("SNR too low: $snr dB", snr > 28.0)
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

        val snr = snrDb(down, 16000, 1000.0, 8000.0)
        assertTrue("round-trip SNR too low: $snr dB", snr > 25.0)
    }
}
