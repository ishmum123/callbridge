package bd.callbridge.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pinned to API 34: Robolectric 4.13 (this project's version) doesn't yet support the
 *  project's targetSdk 36 as a simulated platform. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureWavDumperTest {

    private fun newestCaptureFile(): File {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "captures")
        return dir.listFiles()?.maxByOrNull { it.lastModified() }
            ?: error("no capture file found under ${dir.absolutePath}")
    }

    @Test
    fun `writes a valid 44-byte RIFF WAV header and correct data length after stop`() {
        val context = RuntimeEnvironment.getApplication()
        val dumper = CaptureWavDumper(context, sampleRateHz = 8000)
        val pcm = shortArrayOf(1, 2, 3, -4, 100, -100)

        dumper.start()
        dumper.write(pcm)
        dumper.stop()

        val bytes = newestCaptureFile().readBytes()
        val dataLength = pcm.size * 2

        assertEquals("total file size should be 44-byte header + data", 44 + dataLength, bytes.size)

        fun ascii(offset: Int, len: Int) = String(bytes, offset, len, Charsets.US_ASCII)
        fun u32(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun u16(offset: Int) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

        assertEquals("RIFF", ascii(0, 4))
        assertEquals(36 + dataLength, u32(4))
        assertEquals("WAVE", ascii(8, 4))
        assertEquals("fmt ", ascii(12, 4))
        assertEquals(16, u32(16)) // fmt chunk size
        assertEquals(1, u16(20)) // PCM format
        assertEquals(1, u16(22)) // mono
        assertEquals(8000, u32(24)) // sample rate
        assertEquals(8000 * 1 * 2, u32(28)) // byte rate
        assertEquals(2, u16(32)) // block align
        assertEquals(16, u16(34)) // bits per sample
        assertEquals("data", ascii(36, 4))
        assertEquals(dataLength, u32(40))

        // The payload itself is the little-endian PCM samples, verbatim.
        for (i in pcm.indices) {
            assertEquals(pcm[i], ByteBuffer.wrap(bytes, 44 + i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short)
        }
    }

    @Test
    fun `stops accepting writes once the size cap is reached`() {
        val context = RuntimeEnvironment.getApplication()
        val cap = 44L + 10 // header + room for 5 samples
        val dumper = CaptureWavDumper(context, sampleRateHz = 8000, maxFileSizeBytes = cap)

        dumper.start()
        dumper.write(ShortArray(50) { it.toShort() }) // far more than the cap allows
        dumper.write(ShortArray(50) { it.toShort() }) // further writes should be dropped outright
        dumper.stop()

        val bytes = newestCaptureFile().readBytes()
        assertTrue("file size ${bytes.size} should not exceed cap $cap", bytes.size <= cap)
    }
}
