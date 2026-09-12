package bd.callbridge.gemini

import kotlinx.coroutines.flow.Flow

/** A caller's profile injected into the system prompt (spec §6). */
data class CallerProfile(
    val number: String,
    val name: String?,
    val village: String?,
    val occupation: String?,
)

/** One event emitted by an open [LiveSession]. */
sealed interface LiveSessionEvent {
    /** 24 kHz mono PCM16 audio chunk from Gemini, ready to resample to 8 kHz and inject. */
    data class AudioOut(val pcm: ShortArray) : LiveSessionEvent

    data class InputTranscript(val text: String) : LiveSessionEvent

    data class OutputTranscript(val text: String) : LiveSessionEvent

    data class Error(val message: String, val cause: Throwable? = null) : LiveSessionEvent

    data object Closed : LiveSessionEvent
}

/**
 * WebSocket session against the Gemini Live API (spec §4.4). M0 ships only the interface and a
 * stub; the Gemini-session worker implements the real OkHttp WebSocket client.
 *
 * HANDOFF (Gemini-session worker):
 *  - Verify the current Flash Live model id in the Gemini API docs before replacing
 *    [bd.callbridge.Config.GEMINI_MODEL_ID].
 *  - Setup message: response modality audio, Bangla voice, system prompt (spec §6) with
 *    [CallerProfile] interpolated, input/output transcription enabled.
 *  - [sendAudio] takes 16 kHz PCM16 mono chunks (~100 ms, spec §4.4).
 *  - [interrupt] must map to the Live API's barge-in / activityStart signal and should also
 *    be triggered locally on our own VAD firing while [LiveSessionEvent.AudioOut] is playing.
 *  - Implement the 8 s watchdog (no socket message during an active call -> reopen, spec §4.4)
 *    at the call-orchestration layer that owns this session, not inside the implementation
 *    itself, so it stays unit-testable.
 */
interface LiveSession {
    /** Opens the socket and sends the setup message. Suspends until setup is acknowledged. */
    suspend fun open(systemPrompt: String, profile: CallerProfile)

    /** Streams a 16 kHz PCM16 mono chunk to Gemini. */
    fun sendAudio(pcm: ShortArray)

    /** Signals barge-in: stop generating / discard in-flight output. */
    fun interrupt()

    /** Session events; terminates with [LiveSessionEvent.Closed] or an [LiveSessionEvent.Error]. */
    val events: Flow<LiveSessionEvent>

    suspend fun close()
}

/** Placeholder until the Gemini-session worker lands the OkHttp WebSocket implementation. */
class UnimplementedLiveSession : LiveSession {
    override suspend fun open(systemPrompt: String, profile: CallerProfile): Nothing =
        throw NotImplementedError("LiveSession is implemented by the Gemini-session milestone (M2).")

    override fun sendAudio(pcm: ShortArray): Nothing =
        throw NotImplementedError("LiveSession is implemented by the Gemini-session milestone (M2).")

    override fun interrupt(): Nothing =
        throw NotImplementedError("LiveSession is implemented by the Gemini-session milestone (M2).")

    override val events: Flow<LiveSessionEvent>
        get() = throw NotImplementedError("LiveSession is implemented by the Gemini-session milestone (M2).")

    override suspend fun close(): Nothing =
        throw NotImplementedError("LiveSession is implemented by the Gemini-session milestone (M2).")
}
