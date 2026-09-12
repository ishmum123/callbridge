package bd.callbridge.audio

/**
 * Plays PCM audio into the call uplink so the caller hears Gemini's reply (spec §4.3).
 * A/B/C route implementations are selected by [bd.callbridge.Config.injectorRoute] via
 * [InjectorFactory]. Concrete implementations:
 *  - [IncallMusicInjector] (Route A): research finding — genuinely not implementable from an
 *    app process on this OS version without unstable native ABI access; see
 *    app/src/main/cpp/audioclient_probe.cpp for the full writeup. Always reports unavailable.
 *  - [TelephonyTxInjector] (Route B): plain `android.media.AudioTrack` + `setPreferredDevice`
 *    targeting the TELEPHONY_TX `AudioDeviceInfo`. The real primary path (spec's own order is
 *    "Route B -> Route A -> Route C").
 *  - [LoopbackInjector] (Route C): plays to the default speaker AudioTrack; always works,
 *    lowest quality, used with the physical padded-box loopback rig.
 * All take mono PCM16 input at [bd.callbridge.Config.CAPTURE_SAMPLE_RATE_HZ] (16 kHz);
 * resampling from the Gemini 24 kHz output happens in the Gemini-session/bridge layer before
 * calling [write]. Implementations that need stereo (Route B's preferred 16 kHz/stereo mode,
 * matching docs/hal-recon.md's `incall_music_uplink` mixPort) duplicate the mono channel
 * internally rather than changing this interface's contract.
 */
interface Injector {
    val route: InjectorRoute

    /**
     * The sample rate actually negotiated for output audio once [open] has run — e.g.
     * [TelephonyTxInjector] tries 16 kHz/stereo first and falls back to 8 kHz/mono, so this can
     * only be known post-open, not assumed from a fixed constant (M3 code review finding: a
     * caller resampling Gemini's 24 kHz output must resample to *this* rate, not a hardcoded
     * 16 kHz, or it gets half-speed/garbled audio on the 8 kHz fallback). Null before [open] (or
     * for a route where the concept doesn't apply, e.g. [IncallMusicInjector], which never
     * actually plays anything).
     */
    val openSampleRateHz: Int?

    /** Opens the output track/device. Idempotent. */
    fun open()

    /** Enqueues PCM16 mono samples for playback. */
    fun write(pcm: ShortArray)

    /** Drops any queued/buffered audio immediately (used on barge-in, spec §4.4). */
    fun flush()

    fun close()

    /** Route-specific diagnostic snapshot — surfaced on the status screen (spec §4.6) and used
     *  by the M1b on-device test procedure (docs/injection-routes.md) to tell "opened but
     *  silent" apart from "never routed". Safe to call before [open] (routes should return a
     *  not-yet-opened [RouteProbe] rather than throwing). */
    fun probe(): RouteProbe

    /**
     * M1b acceptance signal (spec §7): a 1 kHz tone, audible on the far phone, proves the route
     * works end to end. Default implementation just opens (if needed) and writes
     * [TestTone.generate]; routes needing extra state around a tone (e.g. Route A, whose probe
     * always reports unavailable) can override.
     */
    fun playTestTone(durationMs: Int = TestTone.DEFAULT_DURATION_MS) {
        open()
        write(TestTone.generate(durationMs = durationMs))
    }
}

/** Diagnostic result of attempting (or having attempted) to route audio via [route]. Mirrors
 *  what the M1b on-device test procedure needs to record per spec §7 ("Keep a `tinymix` dump
 *  of the working state" — this is the app-level equivalent). */
data class RouteProbe(
    val route: InjectorRoute,
    /** Route B: whether a `TYPE_TELEPHONY` `AudioDeviceInfo` was found via
     *  `AudioManager.getDevices`. Route A: whether `libaudioclient.so` was reachable at all
     *  (diagnostic only — never implies the route works, see [IncallMusicInjector]). Route C /
     *  NOOP: always true (nothing to find). */
    val deviceFound: Boolean,
    /** Route B: the return value of `AudioTrack.setPreferredDevice()`. Other routes: mirrors
     *  [deviceFound] since there's no separate "set preferred device" step. */
    val preferredDeviceSet: Boolean,
    /** `AudioTrack.getRoutedDevice()?.id` once playing, or null before `open()`/`play()`. */
    val routedDeviceId: Int? = null,
    /** Free-text explanation for logs / the status screen's "why" tooltip. */
    val detail: String = "",
)

/** Default injector until a real route is wired up (M1b). Logs and drops audio. */
class NoopInjector : Injector {
    override val route: InjectorRoute = InjectorRoute.NOOP

    override val openSampleRateHz: Int? = null

    override fun open() = Unit

    override fun write(pcm: ShortArray) = Unit

    override fun flush() = Unit

    override fun close() = Unit

    override fun probe(): RouteProbe = RouteProbe(
        route = route,
        deviceFound = false,
        preferredDeviceSet = false,
        detail = "NOOP route: no injection wired up, audio is dropped",
    )
}
