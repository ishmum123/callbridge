package bd.callbridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class TestToneTest {

    @Test
    fun `sample count matches sample rate times duration`() {
        val pcm = TestTone.generate(sampleRateHz = 16_000, durationMs = 2_000)
        assertEquals(32_000, pcm.size)
    }

    @Test
    fun `sample count scales with a fractional duration`() {
        val pcm = TestTone.generate(sampleRateHz = 8_000, durationMs = 250)
        assertEquals(2_000, pcm.size)
    }

    @Test
    fun `first sample is at phase zero`() {
        val pcm = TestTone.generate(sampleRateHz = 16_000, durationMs = 100, frequencyHz = 1000.0)
        assertEquals(0, pcm[0].toInt())
    }

    @Test
    fun `amplitude stays within the requested fraction of full scale`() {
        val amplitude = 0.6
        val pcm = TestTone.generate(sampleRateHz = 16_000, durationMs = 50, amplitude = amplitude)
        val maxAbs = pcm.maxOf { abs(it.toInt()) }
        val expectedMax = (amplitude * Short.MAX_VALUE).toInt()
        // Allow a small margin: the sampled sine won't necessarily hit its exact peak within a
        // short buffer, but it must never exceed the requested scale (clipping check).
        assertTrue("maxAbs=$maxAbs should not exceed expectedMax=$expectedMax", maxAbs <= expectedMax)
    }

    @Test
    fun `matches a hand-computed sine at a known sample index`() {
        val sampleRate = 16_000
        val frequency = 1000.0
        val pcm = TestTone.generate(sampleRateHz = sampleRate, durationMs = 100, frequencyHz = frequency, amplitude = 1.0)
        val sampleIndex = 4
        val expected = (Short.MAX_VALUE * sin(2.0 * Math.PI * frequency * sampleIndex / sampleRate)).toInt()
        // toInt() truncation in the implementation vs. this test can differ by at most 1 ULP.
        assertTrue(abs(pcm[sampleIndex].toInt() - expected) <= 1)
    }

    @Test
    fun `rejects non-positive sample rate`() {
        assertThrows(IllegalArgumentException::class.java) {
            TestTone.generate(sampleRateHz = 0, durationMs = 100)
        }
    }

    @Test
    fun `rejects non-positive duration`() {
        assertThrows(IllegalArgumentException::class.java) {
            TestTone.generate(sampleRateHz = 16_000, durationMs = 0)
        }
    }

    @Test
    fun `rejects amplitude outside 0 to 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            TestTone.generate(sampleRateHz = 16_000, durationMs = 100, amplitude = 1.5)
        }
    }
}
