package bd.callbridge.gemini

import kotlinx.coroutines.flow.Flow

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
    /** 24 kHz mono PCM16 audio chunk from Gemini, ready to resample to 8 kHz and inject. */
    data class AudioOut(val pcm: ShortArray) : LiveSessionEvent

    data class InputTranscript(val text: String) : LiveSessionEvent

    data class OutputTranscript(val text: String) : LiveSessionEvent

    /** Server discarded/cancelled the in-flight model turn (barge-in). */
    data object Interrupted : LiveSessionEvent

    /** The model finished a turn (`serverContent.turnComplete`). */
    data object TurnComplete : LiveSessionEvent

    /** Watchdog timeout, socket failure, or a server error message. */
    data class Error(val message: String, val cause: Throwable? = null) : LiveSessionEvent

    data object Closed : LiveSessionEvent
}

/**
 * WebSocket session against the Gemini Live API (spec §4.4).
 *
 * Implemented by [GeminiLiveSession]. See `docs/gemini-live.md` for the verified message shapes,
 * VAD-mode findings, and pricing sources.
 */
interface LiveSession {
    /** Opens the socket and sends the setup message. Suspends until setup is acknowledged. */
    suspend fun open(systemPrompt: String, profile: CallerProfile)

    /** Streams a 16 kHz PCM16 mono chunk (~100 ms) to Gemini as `realtimeInput.audio`. */
    fun sendAudio(pcm: ShortArray)

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

    /** Session events; terminates with [LiveSessionEvent.Closed] or an [LiveSessionEvent.Error]. */
    val events: Flow<LiveSessionEvent>

    suspend fun close()
}

/** Placeholder retained for tests/wiring that want a no-op session without a real socket. */
class UnimplementedLiveSession : LiveSession {
    override suspend fun open(systemPrompt: String, profile: CallerProfile): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun sendAudio(pcm: ShortArray): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun sendActivityStart(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun sendActivityEnd(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override fun interrupt(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override val events: Flow<LiveSessionEvent>
        get() = throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")

    override suspend fun close(): Nothing =
        throw NotImplementedError("Use GeminiLiveSession for a real Live API connection.")
}
