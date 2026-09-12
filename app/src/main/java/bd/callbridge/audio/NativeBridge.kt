package bd.callbridge.audio

/**
 * JNI bridge into libcallbridge_native.so.
 *
 * M1b (spec §4.3) adds:
 *  - [nativeProbeAudioClient]: Route A research probe (see app/src/main/cpp/audioclient_probe.cpp
 *    for the full writeup) — reports whether `libaudioclient.so` is reachable at all from this
 *    process, but deliberately does NOT construct a native `android::AudioTrack` (unstable ABI
 *    across releases, not worth the crash risk without a live device to verify against).
 *    [IncallMusicInjector] treats Route A as unavailable regardless of this probe's result.
 *  - mixer control JNI methods live directly on [MixerControl] (`nativeGetControl`/
 *    `nativeSetControl`), not here, so a caller that only needs the mixer doesn't have to touch
 *    this object's classloading (both still share the one native library).
 *
 * Callers must catch [UnsatisfiedLinkError] around any use of this object on non-Android JVMs
 * (unit tests) — the `init` block below loads a real .so that only exists on-device.
 */
object NativeBridge {
    init {
        System.loadLibrary("callbridge_native")
    }

    @JvmStatic
    external fun nativeVersion(): String

    /** See [IncallMusicInjector] / app/src/main/cpp/audioclient_probe.cpp. Never throws for a
     *  missing library — returns a diagnostic string either way. */
    @JvmStatic
    external fun nativeProbeAudioClient(): String
}
