package bd.callbridge

import bd.callbridge.audio.InjectorRoute

/**
 * Central config flags for the pilot build. Later milestones may move some of this to a
 * DataStore/SharedPreferences-backed settings screen; for M0 these are compile-time constants
 * plus one BuildConfig-sourced secret.
 */
object Config {
    /** Selects which [bd.callbridge.audio.Injector] implementation BridgeForegroundService wires up.
     *  Route B (TELEPHONY_TX) proven on-device 2026-09-12: tone injected via AudioTrack preferred
     *  device TYPE_TELEPHONY was heard on the far phone (see docs/injection-routes.md). Set NOOP
     *  for a plain non-rooted build. */
    val injectorRoute: InjectorRoute = InjectorRoute.TELEPHONY_TX

    /** Demo decision 2026-09-12: Gemini server-side VAD (robust to noise/backchannels; barge-in
     *  is the server's Interrupted event). LOCAL_VAD keeps the energy-gate path selectable. */
    val vadMode: bd.callbridge.gemini.VadMode = bd.callbridge.gemini.VadMode.GEMINI_VAD

    /** Verified Gemini Live model ids (2026-09-12). [GEMINI_MODEL_ID] is the primary model the
     *  Gemini-session worker (M2) should open; [GEMINI_MODEL_FALLBACK] is used if the primary
     *  model/region is unavailable. Audio contract unchanged: 16 kHz PCM16 in, 24 kHz PCM16 out
     *  (spec §2). */
    const val GEMINI_MODEL_ID: String = "gemini-3.1-flash-live-preview"
    const val GEMINI_MODEL_FALLBACK: String = "gemini-2.5-flash-native-audio-preview-12-2025"

    /** Text/JSON model for the Patient profile summarizer's REST `generateContent` calls (NOT the
     *  Live API — this is a plain request/response call, no WebSocket). Verified 2026-09-12 via
     *  `ProfileSummarizerSmokeTest` against the real key. */
    const val GEMINI_SUMMARY_MODEL_ID: String = "gemini-2.5-flash"

    /** Read from local.properties -> BuildConfig at build time. Never commit a real key. */
    val geminiApiKey: String get() = BuildConfig.GEMINI_API_KEY

    /** Seconds to wait after rejecting an unknown caller before calling back (spec §3, §4.1). */
    const val CALLBACK_DELAY_SECONDS: Long = 2L

    /**
     * Demo/hackathon policy: answer every caller immediately instead of the spec §3
     * reject-then-callback flow for unregistered numbers. Set false for the shop pilot.
     */
    const val ANSWER_UNREGISTERED_CALLERS: Boolean = true

    const val CAPTURE_SAMPLE_RATE_HZ: Int = 16_000
    const val GEMINI_OUTPUT_SAMPLE_RATE_HZ: Int = 24_000
}
