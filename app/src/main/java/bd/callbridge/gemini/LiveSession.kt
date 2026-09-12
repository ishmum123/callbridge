package bd.callbridge.gemini

import bd.callbridge.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A caller's profile injected into the system prompt (spec §6). */
data class CallerProfile(
    val number: String,
    val name: String?,
    val village: String?,
    val occupation: String?,
)

/**
 * Voice-activity-detection mode for a [LiveSession] (spec §4.4, §10 "Gemini-side VAD vs ours").
 *  - [GEMINI_VAD]: Live API's automatic activity detection stays enabled server-side; we just
 *    stream audio continuously and let Gemini decide turn boundaries.
 *  - [LOCAL_VAD]: automatic activity detection is disabled in the setup message; our own VAD
 *    gate (audio-pipeline milestone) drives [LiveSession.sendActivityStart]/[sendActivityEnd].
 */
enum class VadMode { GEMINI_VAD, LOCAL_VAD }

/** One event emitted by an open [LiveSession]. */
sealed interface LiveSessionEvent {
    /**
     * PCM16 audio chunk from Gemini, ready to resample (from [sampleRate], normally
     * [Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ] = 24 kHz) to 8 kHz and inject. [sampleRate] is parsed
     * from the server's `mimeType` (e.g. `audio/pcm;rate=24000`) rather than assumed, so a model
     * change that alters the output rate is observable instead of silently mis-resampled.
     */
    data class AudioOut(
        val pcm: ShortArray,
        val sampleRate: Int = Config.GEMINI_OUTPUT_SAMPLE_RATE_HZ,
    ) : LiveSessionEvent

    data class InputTranscript(val text: String) : LiveSessionEvent

    data class OutputTranscript(val text: String) : LiveSessionEvent

    /** Server acknowledged the setup message (`setupComplete`); session is ready for audio. */
    data object SetupComplete : LiveSessionEvent

    /** Server discarded/cancelled the in-flight model turn (barge-in). */
    data object Interrupted : LiveSessionEvent

    /** The model finished a turn (`serverContent.turnComplete`). */
    data object TurnComplete : LiveSessionEvent

    /**
     * Watchdog timeout, socket failure, or a server error message. Not always terminal (e.g. a
     * malformed single server message is reported but the session continues) — see
     * [LiveSession.terminalState] for the authoritative terminal signal.
     */
    data class Error(val message: String, val cause: Throwable? = null) : LiveSessionEvent

    data object Closed : LiveSessionEvent
}

/**
 * Authoritative terminal status of a [LiveSession], replayed to late subscribers (unlike
 * [LiveSession.events], which has no replay and can drop events emitted before subscription).
 */
sealed interface SessionTerminalState {
    /** Session is open (or not yet opened); no terminal event has occurred. */
    data object Open : SessionTerminalState

    /** Session ended because of an unrecoverable error (watchdog, socket failure, goAway). */
    data class Failed(val message: String, val cause: Throwable? = null) : SessionTerminalState

    /** Session ended cleanly (client-initiated close, or a clean server close). */
    data object Closed : SessionTerminalState
}

/**
 * WebSocket session against the Gemini Live API (spec §4.4).
 *
 * Implemented by [GeminiLiveSession]. See `docs/gemini-live.md` for the verified message shapes,
 * VAD-mode findings, and pricing sources.
 */
interface LiveSession {
    /**
     * Opens the socket and sends the setup message. Suspends until setup is acknowledged.
     *
     * Callers **must subscribe to [events] (and/or [terminalState]) before calling [open]**: the
     * event stream has no replay, so a setup-failure [LiveSessionEvent.Error] emitted during
     * [open] itself would be missed by a subscriber that attaches afterwards. [terminalState]
     * replays regardless, but the ordinary [LiveSessionEvent] stream does not.
     *
     * A [LiveSession] is single-use: calling [open] a second time on the same instance throws
     * [IllegalStateException]. Open a new instance per call.
     */
    suspend fun open(systemPrompt: String, profile: CallerProfile)

    /**
     * Streams a 16 kHz PCM16 mono chunk (~100 ms) to Gemini as `realtimeInput.audio`. Under
     * backpressure (see [droppedAudioChunkCount]) the chunk may be silently dropped rather than
     * blocking the caller or growing the socket's outbound queue unbounded.
     */
    fun sendAudio(pcm: ShortArray)

    /** Count of audio chunks dropped so far because the outbound socket queue was too full. */
    val droppedAudioChunkCount: Long

    /**
     * Manual VAD signal: caller started speaking. Only meaningful with [VadMode.LOCAL_VAD]
     * (automatic activity detection disabled in setup) — no-op otherwise.
     */
    fun sendActivityStart()

    /** Manual VAD signal: caller stopped speaking. See [sendActivityStart]. */
    fun sendActivityEnd()

    /**
     * Signals barge-in. With [VadMode.LOCAL_VAD] this sends `activityStart`, which under the
     * default `activityHandling: START_OF_ACTIVITY_INTERRUPTS` cancels the in-flight generation
     * server-side. With [VadMode.GEMINI_VAD] this is a no-op: the server's own VAD already
     * detects the caller's speech and interrupts on its own.
     */
    fun interrupt()

    /**
     * Session events; not replayed (a late subscriber misses events emitted before it
     * subscribes) and audio/transcript events may be dropped under sustained backpressure — see
     * [terminalState] for a replayed, drop-proof terminal signal.
     */
    val events: Flow<LiveSessionEvent>

    /** Replayed terminal status (always has a current value for new subscribers). */
    val terminalState: StateFlow<SessionTerminalState>

    suspend fun close()
}

/** Placeholder retained for tests/wiring that want a no-op session without a real socket. */
class UnimplementedLiveSession : LiveSession {
    override suspend fun open(systemPrompt: String, profile: CallerProfile): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun sendAudio(pcm: ShortArray): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override val droppedAudioChunkCount: Long = 0L

    override fun sendActivityStart(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun sendActivityEnd(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun interrupt(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override val events: Flow<LiveSessionEvent>
        get() = throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override val terminalState: StateFlow<SessionTerminalState>
        get() = throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override suspend fun close(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")
}
