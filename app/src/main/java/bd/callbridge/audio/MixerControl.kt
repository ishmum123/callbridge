package bd.callbridge.audio

import android.util.Log

/**
 * Toggles ALSA mixer controls by name (spec §4.3 step 3), for when a route is opened but the
 * caller hears nothing and the HAL's Route A switch needs flipping by hand:
 * `Incall_Music Audio Mixer MultiMedia9` / `Incall_Music_2 Audio Mixer MultiMedia9`
 * (docs/hal-recon.md, mixer_paths.xml lines 3591/3702).
 *
 * There is no `tinymix` binary and no `su` on the pilot phone (docs/hal-recon.md /
 * docs/STATUS.md), so this talks to `/dev/snd/controlC<card>` directly via the kernel ALSA
 * control-interface ioctls (`app/src/main/cpp/alsa_mixer.cpp`) instead of shelling out.
 *
 * The app is a priv-app but is **not guaranteed** to be in the `audio` group, so opening the
 * control device can fail with EACCES — [get]/[set] report that as [MixerResult.PermissionDenied]
 * rather than crashing, per the brief.
 *
 * Name validation ([AlsaControlNames.isValid]) is split into its own dependency-free object so
 * it — and anything else pure — stays unit-testable on a plain JVM: referencing this object at
 * all runs its `init` block ([loadNativeLibrary]), which throws [UnsatisfiedLinkError] off-device.
 */
object MixerControl {
    private val nativeLibraryError: String? = loadNativeLibrary()

    private fun loadNativeLibrary(): String? = try {
        System.loadLibrary("callbridge_native")
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "UnsatisfiedLinkError"
    }

    /** Reads the current value of the named mixer control on ALSA card [card] (0 = onboard
     *  `kona-mtp-snd-card` per docs/hal-recon.md). */
    fun get(name: String, card: Int = DEFAULT_CARD): MixerResult {
        require(AlsaControlNames.isValid(name)) { "invalid control name: $name" }
        nativeLibraryError?.let { return MixerResult.Unavailable("native library unavailable: $it") }
        val rc = nativeGetControl(card, name)
        return toResult(rc, name)
    }

    /** Sets the named mixer control on ALSA card [card] to [value] (controls we care about are
     *  boolean switches, so callers pass 0 or 1). */
    fun set(name: String, value: Int, card: Int = DEFAULT_CARD): MixerResult {
        require(AlsaControlNames.isValid(name)) { "invalid control name: $name" }
        nativeLibraryError?.let { return MixerResult.Unavailable("native library unavailable: $it") }
        val rc = nativeSetControl(card, name, value)
        return toResult(rc, name)
    }

    private fun toResult(rc: Int, name: String): MixerResult = when {
        rc >= 0 -> MixerResult.Value(rc)
        rc == -EACCES -> MixerResult.PermissionDenied(name)
        rc == -ENOENT -> MixerResult.NotFound(name)
        else -> MixerResult.Error(name, -rc)
    }

    // errno values from bionic's <errno.h>, stable across API levels.
    private const val EACCES = 13
    private const val ENOENT = 2

    private const val DEFAULT_CARD = 0

    @JvmStatic
    private external fun nativeGetControl(card: Int, name: String): Int

    @JvmStatic
    private external fun nativeSetControl(card: Int, name: String, value: Int): Int
}

/** Pure control-name validation, split out of [MixerControl] so it's testable on a plain JVM
 *  without touching `System.loadLibrary` (see [MixerControl] doc comment). */
object AlsaControlNames {
    /** ALSA control names are a fixed 44-byte buffer on the kernel side
     *  (`SNDRV_CTL_ELEM_ID_NAME_MAXLEN`, sound/asound.h) — reject anything that can't round-trip
     *  before it ever reaches JNI. */
    const val MAX_LEN: Int = 44

    fun isValid(name: String): Boolean =
        name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size < MAX_LEN
}

/** Result of a [MixerControl.get]/[MixerControl.set] call. Native errno failures are mapped to
 *  named cases rather than surfaced as raw negative ints, per the brief's "must handle
 *  permission failure gracefully" requirement. */
sealed class MixerResult {
    data class Value(val value: Int) : MixerResult()
    data class PermissionDenied(val controlName: String) : MixerResult()
    data class NotFound(val controlName: String) : MixerResult()
    data class Error(val controlName: String, val errno: Int) : MixerResult()
    data class Unavailable(val reason: String) : MixerResult()
}

/** Logs the outcome at an appropriate level; convenience for call sites that just want a
 *  fire-and-forget toggle with a trace of what happened. */
fun MixerResult.logOutcome(tag: String, action: String) {
    when (this) {
        is MixerResult.Value -> Log.i(tag, "$action succeeded: value=$value")
        is MixerResult.PermissionDenied -> Log.w(tag, "$action denied (no audio group?): $controlName")
        is MixerResult.NotFound -> Log.w(tag, "$action: control not found: $controlName")
        is MixerResult.Error -> Log.w(tag, "$action failed: $controlName errno=$errno")
        is MixerResult.Unavailable -> Log.w(tag, "$action unavailable: $reason")
    }
}
