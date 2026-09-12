package bd.callbridge.audio

/**
 * Plays PCM audio into the call uplink so the caller hears Gemini's reply (spec §4.3).
 * A/B/C route implementations are selected by [bd.callbridge.Config.injectorRoute].
 *
 * HANDOFF (injector/NDK worker): implement
 *  - `InCallMusicInjector` (Route A): JNI calls into [NativeBridge] backed by a native
 *    AudioTrack opened with AUDIO_OUTPUT_FLAG_INCALL_MUSIC (see app/src/main/cpp/native.cpp,
 *    currently a stub exposing only `nativeVersion()`). Needs the `tinymix` control toggling
 *    described in spec §4.3 step 3, likely shelled out via `su -c tinymix ...` at call start.
 *  - `TelephonyTxInjector` (Route B): plain `android.media.AudioTrack` + `setPreferredDevice`
 *    targeting the TELEPHONY_TX `AudioDeviceInfo`, needs `MODIFY_AUDIO_ROUTING` (priv-app).
 *  - `LoopbackInjector` (Route C): just plays to the default speaker AudioTrack; always works,
 *    lowest quality, used as the fallback per spec §4.3/§7 M1b.
 * All three take 8 kHz (or 16 kHz, HAL-dependent) mono PCM16 input; resampling from the
 * Gemini 24 kHz output happens in the Gemini-session/bridge layer before calling [write].
 */
interface Injector {
    val route: InjectorRoute

    /** Opens the output track/device. Idempotent. */
    fun open()

    /** Enqueues PCM16 mono samples for playback. */
    fun write(pcm: ShortArray)

    /** Drops any queued/buffered audio immediately (used on barge-in, spec §4.4). */
    fun flush()

    fun close()
}

/** Default injector until a real route is wired up (M1b). Logs and drops audio. */
class NoopInjector : Injector {
    override val route: InjectorRoute = InjectorRoute.NOOP

    override fun open() = Unit

    override fun write(pcm: ShortArray) = Unit

    override fun flush() = Unit

    override fun close() = Unit
}
