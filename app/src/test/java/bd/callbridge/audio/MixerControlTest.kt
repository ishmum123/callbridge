package bd.callbridge.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AlsaControlNames] is pure (no JNI), so it's tested directly. [MixerControl] itself catches
 *  its own `UnsatisfiedLinkError` from `System.loadLibrary` (there's no native library on a
 *  plain JVM), so `get`/`set` are also exercised here — they must report
 *  [MixerResult.Unavailable] instead of throwing. */
class MixerControlTest {

    @Test
    fun `accepts the real hal-recon control names`() {
        assertTrue(AlsaControlNames.isValid("Incall_Music Audio Mixer MultiMedia9"))
        assertTrue(AlsaControlNames.isValid("Incall_Music_2 Audio Mixer MultiMedia9"))
    }

    @Test
    fun `rejects blank names`() {
        assertFalse(AlsaControlNames.isValid(""))
        assertFalse(AlsaControlNames.isValid("   "))
    }

    @Test
    fun `rejects names at or above the kernel's 44-byte buffer`() {
        val exactly44 = "a".repeat(44)
        val exactly43 = "a".repeat(43)
        assertFalse(AlsaControlNames.isValid(exactly44))
        assertTrue(AlsaControlNames.isValid(exactly43))
    }

    @Test
    fun `counts UTF-8 bytes not chars for multi-byte names`() {
        // Each of these is a 3-byte UTF-8 character; 15 of them is 45 bytes, over the limit,
        // even though the Kotlin String.length is only 15.
        val name = "ব".repeat(15)
        assertFalse(AlsaControlNames.isValid(name))
    }

    @Test
    fun `get rejects an invalid name before touching native code`() {
        assertThrows(IllegalArgumentException::class.java) {
            MixerControl.get("")
        }
    }

    @Test
    fun `set rejects an invalid name before touching native code`() {
        assertThrows(IllegalArgumentException::class.java) {
            MixerControl.set("", value = 1)
        }
    }

    @Test
    fun `get reports Unavailable off-device rather than throwing`() {
        val result = MixerControl.get("Incall_Music Audio Mixer MultiMedia9")
        assertTrue(result is MixerResult.Unavailable)
    }

    @Test
    fun `set reports Unavailable off-device rather than throwing`() {
        val result = MixerControl.set("Incall_Music Audio Mixer MultiMedia9", value = 1)
        assertTrue(result is MixerResult.Unavailable)
    }
}
