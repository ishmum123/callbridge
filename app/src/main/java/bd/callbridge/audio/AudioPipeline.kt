package bd.callbridge.audio

import bd.callbridge.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
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
     *  can coalesce or miss events, but will never stall the audio path upstream. */
    val vadEvents: SharedFlow<VadGate.Event> = _vadEvents.asSharedFlow()

    /** 16 kHz mono PCM16 chunks of [chunkMs] duration, gated by a per-collection [VadGate]
     *  (see [vadGateFactory]). The final, possibly
     *  short, chunk of each utterance is flushed (zero-padded to [chunkSamples]) the moment
     *  [VadGate.Event.SpeechEnded] fires, rather than withheld until the next utterance fills it
     *  out — otherwise up to one chunk's worth of trailing audio would be delayed and end up
     *  prepended to the *next* utterance's first chunk. */
    val chunks: Flow<ShortArray> = flow {
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
                    _vadEvents.tryEmit(event)
                    if (event == VadGate.Event.SpeechEnded && chunkBufLen > 0) {
                        // Tail flush: zero-pad the partial chunk rather than withholding it.
                        emit(chunkBuf.copyOf(chunkSamples))
                        chunkBufLen = 0
                    }
                }
                for (outFrame in result.frames) {
                    var fOffset = 0
                    while (fOffset < outFrame.size) {
                        val m = minOf(chunkSamples - chunkBufLen, outFrame.size - fOffset)
                        System.arraycopy(outFrame, fOffset, chunkBuf, chunkBufLen, m)
                        chunkBufLen += m
                        fOffset += m
                        if (chunkBufLen == chunkSamples) {
                            emit(chunkBuf.copyOf())
                            chunkBufLen = 0
                        }
                    }
                }
            }
        }
    }.onStart { capture.start() }.onCompletion { capture.stop() }

    fun start() = capture.start()

    fun stop() = capture.stop()
}
