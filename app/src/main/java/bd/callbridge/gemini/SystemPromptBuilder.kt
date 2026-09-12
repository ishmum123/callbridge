package bd.callbridge.gemini

import android.content.Context
import bd.callbridge.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the Gemini system instruction from the Bangla v0 template (spec §6) at
 * `app/src/main/res/raw/system_prompt_bn.txt`, interpolating the caller profile, today's date,
 * and the shop name.
 */
object SystemPromptBuilder {
    /**
     * Fixed, distinctive Bangla closing sentence the model is instructed to speak (not write as a
     * hidden token) to end every call. The session is AUDIO-only (the Live API does not reliably
     * support AUDIO+TEXT together — see `docs/gemini-live.md`), so a bracket token like `[HANGUP]`
     * can never appear: the model would have to *say* the literal English word, and it never
     * would/should. Detection lives in [TranscriptRecorder]/[HangupPhraseMatcher] against the
     * ASR'd output transcript.
     */
    const val HANGUP_PHRASE = "আল্লাহ হাফেজ, ভালো থাকবেন।"

    /**
     * Text sent via [LiveSession.sendTextTurn] right after `setupComplete` to make the model
     * speak first (M3 greeting kick — code review fix, replacing an empty
     * `activityStart`/`activityEnd` pair that armed the response watchdog). Relies on the
     * template's own "শুরুতে সংক্ষেপে নিজের পরিচয় দাও..." instruction for what to actually say.
     */
    const val GREETING_TRIGGER = "কল শুরু হয়েছে, নিজের পরিচয় দাও।"

    // Fixed to Locale.US (English month names) rather than the device default so tests and
    // production stay deterministic regardless of the phone's locale.
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US)

    /** Reads the raw template resource. Requires an Android [Context]; see [interpolate] for the
     *  pure/testable half of this. */
    fun loadTemplate(context: Context): String =
        context.resources.openRawResource(R.raw.system_prompt_bn).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun build(
        context: Context,
        profile: CallerProfile,
        shopName: String,
        today: LocalDate = LocalDate.now(),
    ): String = interpolate(loadTemplate(context), profile, shopName, today)

    /**
     * Pure interpolation, independent of Android — this is what unit tests exercise.
     *
     * `{caller_line}` is built dynamically from only the known (non-blank) profile fields, and
     * omitted entirely (empty string) when none are known — rather than rendering a line full of
     * "অজানা" (unknown) placeholders. The individual `{name}`/`{village}`/`{occupation}`
     * placeholders are still substituted independently (falling back to "অজানা") for any template
     * that wants per-field access rather than the composed line.
     */
    fun interpolate(
        template: String,
        profile: CallerProfile,
        shopName: String,
        today: LocalDate,
    ): String {
        val callerLine = buildCallerLine(profile)
        return template
            .replace("{caller_line}", callerLine)
            .replace("{name}", profile.name?.ifBlank { null } ?: "অজানা")
            .replace("{village}", profile.village?.ifBlank { null } ?: "অজানা")
            .replace("{occupation}", profile.occupation?.ifBlank { null } ?: "অজানা")
            .replace("{date}", today.format(DATE_FORMAT))
            .replace("{shop}", shopName)
    }

    private fun buildCallerLine(profile: CallerProfile): String {
        val parts = listOfNotNull(
            profile.name?.takeIf { it.isNotBlank() }?.let { "নাম $it" },
            profile.village?.takeIf { it.isNotBlank() }?.let { "গ্রাম $it" },
            profile.occupation?.takeIf { it.isNotBlank() }?.let { "পেশা $it" },
        )
        if (parts.isEmpty()) return ""
        return "কলারের তথ্য: ${parts.joinToString(", ")}।\n\n"
    }
}
