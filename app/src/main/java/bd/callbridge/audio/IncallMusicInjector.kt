package bd.callbridge.audio

import android.util.Log

/**
 * Route A (spec §4.3 step 2): the Qualcomm in-call-music uplink path. docs/hal-recon.md
 * confirms the HAL fully supports it (`incall_music_uplink` mixPort,
 * `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`, `Telephony Tx` device port) — the blocker is entirely on
 * the app side, and it's a hard one. Researched three options (this brief's (a)/(b)/(c)):
 *
 *  (a) NDK dlopen of `libaudioclient.so`, hand-building a native `android::AudioTrack` with
 *      `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`. Attempted as a *probe only* in
 *      app/src/main/cpp/audioclient_probe.cpp — see that file for the full writeup. Summary:
 *      the library isn't in the app-visible linker namespace (needs a namespace-bypass dlopen,
 *      itself unreliable across OEM/AOSP linker configs), and even if loaded, the current
 *      `android::AudioTrack` constructor (AOSP `main`, frameworks/av/media/libaudioclient)
 *      takes an `AttributionSourceState` (an AIDL-generated Parcelable) by value and depends on
 *      libbinder/libutils/libaudiofoundation ABI that Google does not keep stable or export to
 *      apps. Hand-guessing that layout risks corrupting a process that also owns the live
 *      call's audio path. Not attempted beyond the load-only probe.
 *  (b) Java `AudioAttributes`/`AudioTrack` hidden flags. Checked AOSP's `AudioAttributes.java`
 *      hidden `FLAG_*` constants (FLAG_AUDIBILITY_ENFORCED, FLAG_SECURE, FLAG_SCO, FLAG_BEACON,
 *      FLAG_HW_AV_SYNC, FLAG_HW_HOTWORD, FLAG_BYPASS_INTERRUPTION_POLICY, FLAG_BYPASS_MUTE,
 *      FLAG_LOW_LATENCY, FLAG_DEEP_BUFFER, FLAG_NO_MEDIA_PROJECTION, FLAG_MUTE_HAPTIC,
 *      FLAG_NO_SYSTEM_CAPTURE, FLAG_CAPTURE_PRIVATE, FLAG_CONTENT_SPATIALIZED,
 *      FLAG_NEVER_SPATIALIZE) — none of them, hidden or public, map to
 *      `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`. That flag is an `audio_output_flags_t` (HAL/native
 *      concept), and the public+hidden Java `AudioTrack`/`AudioAttributes` surface has no way
 *      to request a specific native output flag at track-creation time. A `HiddenApiBypass`
 *      (LSPosed) style reflection unlock would let us *call* more hidden methods, but there is
 *      no hidden method that takes an output-flags argument to call in the first place — the
 *      bypass doesn't help if the capability isn't there.
 *  (c) `AudioSystem` hidden reflection to open an output with flags. Same blocker as (b): the
 *      hidden `AudioSystem`/`AudioTrack` Java surface doesn't expose `audio_output_flags_t` to
 *      callers; the only place that flag is settable is the native `AudioTrack` constructor
 *      covered by (a).
 *
 * Conclusion: Route A is not implementable from an app process on this OS build with any of
 * the three approaches. This class exists so `InjectorFactory` has something to construct for
 * `InjectorRoute.INCALL_MUSIC`, and so the probe in audioclient_probe.cpp has a caller, but
 * [open] always leaves the route unavailable and [write] is a no-op. Spec's own build order
 * (§4.3/§7 M1b: "Route B -> Route A -> Route C") already tries Route B first, so this doesn't
 * block the milestone — see [TelephonyTxInjector].
 */
class IncallMusicInjector : Injector {
    override val route: InjectorRoute = InjectorRoute.INCALL_MUSIC

    /** Always null: this route never actually plays anything (see class doc). */
    override val openSampleRateHz: Int? = null

    private var probeDetail: String = "not probed yet"
    private var probed = false

    override fun open() {
        if (probed) return
        probed = true
        probeDetail = try {
            NativeBridge.nativeProbeAudioClient()
        } catch (e: UnsatisfiedLinkError) {
            "native library unavailable: ${e.message}"
        } catch (e: Exception) {
            "probe threw: ${e.message}"
        }
        Log.w(TAG, "Route A unavailable regardless of probe result: $probeDetail")
    }

    /** Always a no-op: there is nowhere to write PCM to, see class doc. */
    override fun write(pcm: ShortArray) = Unit

    override fun flush() = Unit

    override fun close() = Unit

    override fun probe(): RouteProbe = RouteProbe(
        route = route,
        deviceFound = false,
        preferredDeviceSet = false,
        detail = "Route A unavailable (see IncallMusicInjector doc comment): $probeDetail",
    )

    companion object {
        private const val TAG = "IncallMusicInjector"
    }
}
