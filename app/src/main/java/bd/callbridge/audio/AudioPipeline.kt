package bd.callbridge.audio

import bd.callbridge.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Composes capture -> [VadGate] -> fixed-size send chunks for the Gemini bridge (spec §4.2/§4.4).
 * Re-frames whatever chunk sizes [AudioCapture] emits (already 16 kHz mono PCM16, per
 * [AudioCapture]'s contract) into [VadGate]'s fixed 20 ms frames, gates them, then re-chunks the
 * gated stream into 100 ms chunks (spec §4.4 send size) for [chunks]. VAD transitions (needed
 * for barge-in) are published on [vadEvents] as they occur.
 *
 * This is what phase 3 hands to `LiveSession`: collecting [chunks] drives [capture] via [start].
 */
class AudioPipeline(
    private val capture: AudioCapture,
    private val vadGate: VadGate = VadGate(),
    sampleRateHz: Int = Config.CAPTURE_SAMPLE_RATE_HZ,
    chunkMs: Int = 100,
) {
    private val chunkSamples = sampleRateHz * chunkMs / 1000

    private val _vadEvents = MutableSharedFlow<VadGate.Event>(extraBufferCapacity = 16)
    val vadEvents: SharedFlow<VadGate.Event> = _vadEvents.asSharedFlow()

    /** 16 kHz mono PCM16 chunks of [chunkMs] duration, gated by [vadGate]. */
    val chunks: Flow<ShortArray> = flow {
        val frameBuf = ArrayDeque<Short>()
        val chunkBuf = ArrayDeque<Short>()
        capture.frames.collect { samples ->
            for (s in samples) {
                frameBuf.addLast(s)
                if (frameBuf.size == vadGate.frameSamples) {
                    val frame = ShortArray(frameBuf.size) { frameBuf[it] }
                    frameBuf.clear()

                    val result = vadGate.process(frame)
                    for (event in result.events) _vadEvents.emit(event)
                    for (outFrame in result.frames) {
                        for (v in outFrame) chunkBuf.addLast(v)
                        while (chunkBuf.size >= chunkSamples) {
                            val chunk = ShortArray(chunkSamples) { chunkBuf.removeFirst() }
                            emit(chunk)
                        }
                    }
                }
            }
        }
    }

    fun start() = capture.start()

    fun stop() = capture.stop()
}
