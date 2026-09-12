package bd.callbridge.gemini

import android.content.Context
import bd.callbridge.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Builds the Gemini system instruction from the Bangla v0 template (spec §6) at
 * `app/src/main/res/raw/system_prompt_bn.txt`, interpolating the caller profile, today's date,
 * and the shop name.
 */
object SystemPromptBuilder {
    /** Literal token the model is instructed to emit in its output transcript to end the call. */
    const val HANGUP_TOKEN = "[HANGUP]"

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")

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

    /** Pure interpolation, independent of Android — this is what unit tests exercise. */
    fun interpolate(
        template: String,
        profile: CallerProfile,
        shopName: String,
        today: LocalDate,
    ): String = template
        .replace("{name}", profile.name?.ifBlank { null } ?: "অজানা")
        .replace("{village}", profile.village?.ifBlank { null } ?: "অজানা")
        .replace("{occupation}", profile.occupation?.ifBlank { null } ?: "অজানা")
        .replace("{date}", today.format(DATE_FORMAT))
        .replace("{shop}", shopName)
}
