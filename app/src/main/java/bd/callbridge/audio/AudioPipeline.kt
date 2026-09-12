package bd.callbridge.audio

import bd.callbridge.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.channels.BufferOverflow

/**
 * Composes capture -> [VadGate] -> fixed-size send chunks for the Gemini bridge (spec §4.2/§4.4).
 * Re-frames whatever chunk sizes [AudioCapture] emits (already 16 kHz mono PCM16, per
 * [AudioCapture]'s contract) into [VadGate]'s fixed 20 ms frames, gates them, then re-chunks the
 * gated stream into 100 ms chunks (spec §4.4 send size) for [chunks]. VAD transitions (needed
 * for barge-in) are published on [vadEvents] as they occur.
 *
 * This is what phase 3 hands to `LiveSession`: collecting [chunks] drives [capture] via
 * `onStart`/`onCompletion` hooks on the flow (also callable explicitly via [start]/[stop], which
 * are idempotent and safe to call redundantly around a collection).
 *
 * A fresh [VadGate] (and fresh re-framing buffers) is built per *collection* of [chunks] via
 * [vadGateFactory] — not shared across collections — so gate state (noise floor, hold timer,
 * pre-roll) never leaks from one call into the next, and concurrent collectors don't stomp on
 * each other's state.
 */
class AudioPipeline(
    private val capture: AudioCapture,
    /** Builds a new [VadGate] for each collection of [chunks]. Defaults to plain [VadGate]
     *  instances; tests/callers that need custom thresholds pass a factory producing those. */
    private val vadGateFactory: () -> VadGate = { VadGate() },
    sampleRateHz: Int = Config.CAPTURE_SAMPLE_RATE_HZ,
    chunkMs: Int = 100,
) {
    private val chunkSamples = sampleRateHz * chunkMs / 1000

    private val _vadEvents = MutableSharedFlow<VadGate.Event>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** VAD transitions, replaying the most recent event to new (e.g. late/re-)subscribers.
     *  Backed by a bounded drop-oldest buffer rather than suspending: a slow/absent subscriber
     *  can coalesce or miss events, but will never stall the audio path upstream. Populated as a
     *  side effect of collecting [chunks]/[events]; kept around for M1a's own tests and any
     *  standalone-VAD-only consumer. **Callers that need audio and VAD events in a single
     *  consistent order (e.g. driving a Live API's manual activity signals around the exact
     *  audio chunks they bound) must use [events] instead** — collecting [chunks] and [vadEvents]
     *  as two independently-scheduled flows does not guarantee their relative delivery order
     *  even though they're produced in order (M3 code review finding). */
    val vadEvents: SharedFlow<VadGate.Event> = _vadEvents.asSharedFlow()

    /** A single ordered stream combining VAD transitions and gated audio chunks, in true
     *  production order — the fix for the ordering hazard described on [vadEvents]. In
     *  particular, the tail-flush chunk for an ending utterance is emitted as a [PipelineEvent.Chunk]
     *  *before* the corresponding [PipelineEvent.Vad]([VadGate.Event.SpeechEnded]) event, so a
     *  single-coroutine consumer that calls `sendAudio` then `sendActivityEnd` in encounter order
     *  never signals "no more audio" before the last chunk of that utterance has actually been
     *  sent. [chunks] is derived from this same flow (via [filterIsInstance]/[map]), so collecting
     *  either drives the same one [capture]/[VadGate] pass — collecting both concurrently on the
     *  same [AudioPipeline] instance is not supported (double-drives capture). */
    val events: Flow<PipelineEvent> = flow {
        val vadGate = vadGateFactory()
        vadGate.reset()

        val frameBuf = ShortArray(vadGate.frameSamples)
        var frameBufLen = 0
        val chunkBuf = ShortArray(chunkSamples)
        var chunkBufLen = 0

        capture.frames.collect { samples ->
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(vadGate.frameSamples - frameBufLen, samples.size - offset)
                System.arraycopy(samples, offset, frameBuf, frameBufLen, n)
                frameBufLen += n
                offset += n
                if (frameBufLen < vadGate.frameSamples) continue
                frameBufLen = 0

                val result = vadGate.process(frameBuf)
                for (event in result.events) {
                    // Tail flush BEFORE the event itself: a consumer processing this single
                    // ordered flow in encounter order (sendAudio, then sendActivityEnd on
                    // SpeechEnded) must see the last chunk of the utterance before the "no more
                    // audio is coming" signal, never after (M3 code review finding).
                    if (event == VadGate.Event.SpeechEnded && chunkBufLen > 0) {
                        // Tail flush: zero-pad the partial chunk rather than withholding it.
                        val tail = chunkBuf.copyOf(chunkSamples)
                        chunkBufLen = 0
                        emit(PipelineEvent.Chunk(tail))
                    }
                    _vadEvents.tryEmit(event)
                    emit(PipelineEvent.Vad(event))
                }
                for (outFrame in result.frames) {
                    var fOffset = 0
                    while (fOffset < outFrame.size) {
                        val m = minOf(chunkSamples - chunkBufLen, outFrame.size - fOffset)
                        System.arraycopy(outFrame, fOffset, chunkBuf, chunkBufLen, m)
                        chunkBufLen += m
                        fOffset += m
                        if (chunkBufLen == chunkSamples) {
                            emit(PipelineEvent.Chunk(chunkBuf.copyOf()))
                            chunkBufLen = 0
                        }
                    }
                }
            }
        }
    }.onStart { capture.start() }.onCompletion { capture.stop() }

    /** 16 kHz mono PCM16 chunks of [chunkMs] duration, gated by a per-collection [VadGate] (see
     *  [vadGateFactory]). Derived from [events] (same underlying capture/gate pass); kept for
     *  M1a's existing tests and any consumer that only needs audio, not VAD transitions. */
    val chunks: Flow<ShortArray> = events.filterIsInstance<PipelineEvent.Chunk>().map { it.pcm }

    fun start() = capture.start()

    fun stop() = capture.stop()

    /** One item of [events]: either a gated audio chunk or a VAD transition, in true production
     *  order (see [events]'s doc comment for why this matters over consuming [chunks]/[vadEvents]
     *  as two separate flows). */
    sealed interface PipelineEvent {
        data class Chunk(val pcm: ShortArray) : PipelineEvent
        data class Vad(val event: VadGate.Event) : PipelineEvent
    }
}
