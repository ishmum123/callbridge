package bd.callbridge.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SystemPromptBuilderTest {
    private val template = "shop={shop} name={name} village={village} occ={occupation} date={date} caller=[{caller_line}]"

    @Test
    fun `interpolates all placeholders`() {
        val profile = CallerProfile(number = "017", name = "Karim", village = "Dumuria", occupation = "কৃষক")
        val result = SystemPromptBuilder.interpolate(template, profile, "রহিম স্টোর", LocalDate.of(2026, 9, 12))

        assertTrue(result.contains("shop=রহিম স্টোর"))
        assertTrue(result.contains("name=Karim"))
        assertTrue(result.contains("village=Dumuria"))
        assertTrue(result.contains("occ=কৃষক"))
        assertTrue(result.contains("date=12 September 2026"))
    }

    @Test
    fun `falls back to unknown for missing per-field placeholders`() {
        val profile = CallerProfile(number = "017", name = null, village = null, occupation = null)
        val result = SystemPromptBuilder.interpolate(template, profile, "shop", LocalDate.of(2026, 1, 1))

        assertTrue(result.contains("name=অজানা"))
        assertTrue(result.contains("village=অজানা"))
        assertTrue(result.contains("occ=অজানা"))
        assertTrue(result.contains("date=1 January 2026"))
    }

    @Test
    fun `composes caller_line from only the known fields`() {
        val profile = CallerProfile(number = "017", name = "Karim", village = null, occupation = "কৃষক")
        val result = SystemPromptBuilder.interpolate(template, profile, "shop", LocalDate.of(2026, 1, 1))

        assertTrue(result.contains("নাম Karim"))
        assertTrue(result.contains("পেশা কৃষক"))
        assertTrue(!result.contains("গ্রাম"))
    }

    @Test
    fun `omits caller_line entirely when the profile is empty`() {
        val profile = CallerProfile(number = "017", name = null, village = null, occupation = null)
        val result = SystemPromptBuilder.interpolate(template, profile, "shop", LocalDate.of(2026, 1, 1))

        assertEquals("caller=[]", result.substringAfter("date=1 January 2026 ").trim())
    }

    @Test
    fun `raw template file contains the hangup phrase and all placeholders`() {
        val text = SystemPromptBuilderTest::class.java.classLoader
            ?.getResourceAsStream("raw/system_prompt_bn.txt")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
        // Robolectric/AGP unit tests don't always expose res/raw on the plain classpath;
        // fall back to reading the source file directly if the resource isn't merged in.
        val content = text ?: java.io.File(
            "src/main/res/raw/system_prompt_bn.txt"
        ).readText(Charsets.UTF_8)

        assertTrue(content.contains("{caller_line}"))
        assertTrue(content.contains("{date}"))
        assertTrue(content.contains("{shop}"))
        assertTrue(content.contains(SystemPromptBuilder.HANGUP_PHRASE))
        assertTrue(!content.contains("[HANGUP]"))
        assertTrue(!content.contains("HANGUP"))
    }
}
