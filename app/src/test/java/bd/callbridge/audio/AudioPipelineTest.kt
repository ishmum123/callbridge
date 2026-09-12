package bd.callbridge.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTest {

    private val frameSamples = 320 // VadGate default, 20 ms @ 16 kHz
    private val chunkSamples = 1600 // AudioPipeline default, 100 ms @ 16 kHz

    /** A capture whose [frames] replays a fixed, pre-baked raw signal split into odd-sized
     *  pieces (mimicking real AudioRecord read sizes not lining up with VAD/chunk framing),
     *  rather than an actual AudioRecord. Emission ends (completing the flow) once the signal
     *  is exhausted. */
    private class FakeAudioCapture(rawSignal: ShortArray, readSizes: IntArray = intArrayOf(333, 1000, 47)) : AudioCapture {
        private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
        override val state: StateFlow<CaptureState> = _state
        override val isCapturing: Boolean get() = _state.value == CaptureState.Running
        var startCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun start() {
            startCalls++
            _state.value = CaptureState.Running
        }

        override fun stop() {
            stopCalls++
            _state.value = CaptureState.Idle
        }

        private val pieces: List<ShortArray> = run {
            val out = mutableListOf<ShortArray>()
            var offset = 0
            var sizeIdx = 0
            while (offset < rawSignal.size) {
                val size = readSizes[sizeIdx % readSizes.size].coerceAtMost(rawSignal.size - offset)
                out += rawSignal.copyOfRange(offset, offset + size)
                offset += size
                sizeIdx++
            }
            out
        }

        override val frames: Flow<ShortArray> = flow { pieces.forEach { emit(it) } }
    }

    private fun silence(n: Int): ShortArray = ShortArray(n)

    private fun tone(n: Int, startSample: Int, freqHz: Double = 1000.0, rate: Int = 16000, amplitude: Double = 12000.0): ShortArray =
        ShortArray(n) { i -> (amplitude * sin(2.0 * PI * freqHz * (startSample + i) / rate)).toInt().toShort() }

    /** Ground truth for how many real (non-padding) samples a plain [VadGate] with pipeline
     *  defaults forwards for [rawSignal], reframed into [frameSamples]-sized pieces exactly like
     *  [AudioPipeline] does internally (any trailing remainder shorter than a full frame is
     *  dropped, matching the pipeline's frameBuf behavior). */
    private fun gatedSampleCount(rawSignal: ShortArray): Long {
        val gate = VadGate()
        var total = 0L
        var offset = 0
        while (offset + frameSamples <= rawSignal.size) {
            val frame = rawSignal.copyOfRange(offset, offset + frameSamples)
            offset += frameSamples
            val result = gate.process(frame)
            for (f in result.frames) total += f.size
        }
        return total
    }

    @Test
    fun `every emitted chunk is exactly chunkSamples and total gated audio matches expectation`() = runTest {
        // silence -> tone -> silence, sized in whole VAD frames (320) so the ground-truth count
        // below is exact, but fed to the pipeline through odd (333/1000/47) read sizes.
        var cursor = 0
        val lead = silence(10 * frameSamples)
        val speech = tone(20 * frameSamples, cursor).also { cursor += it.size }
        val trail = silence(40 * frameSamples) // long enough to run out the default 20-frame hold
        val raw = lead + speech + trail

        val expectedGated = gatedSampleCount(raw)
        val capture = FakeAudioCapture(raw)
        val pipeline = AudioPipeline(capture)

        val chunks = pipeline.chunks.toList()

        assertTrue("expected at least one chunk", chunks.isNotEmpty())
        for (chunk in chunks) {
            assertEquals("every chunk must be exactly $chunkSamples samples", chunkSamples, chunk.size)
        }

        val emittedTotal = chunks.sumOf { it.size }.toLong()
        // Emitted total is always a whole number of chunks, so it can exceed the real gated
        // sample count by up to one chunk's worth of zero padding on the final (tail-flushed)
        // chunk, but never by more than that.
        assertTrue(
            "expected emitted total ($emittedTotal) within one chunk of gated total ($expectedGated)",
            emittedTotal >= expectedGated && emittedTotal - expectedGated < chunkSamples,
        )

        assertEquals(1, capture.startCalls)
        assertEquals(1, capture.stopCalls)
    }

    @Test
    fun `SpeechEnded flushes the utterance tail so it never bleeds into the next utterance's chunks`() = runTest {
        var cursor = 0
        val lead1 = silence(10 * frameSamples)
        val speech1 = tone(20 * frameSamples, cursor).also { cursor += it.size }
        val gap = silence(30 * frameSamples) // long enough to end utterance 1 and refill pre-roll
        val speech2 = tone(20 * frameSamples, cursor).also { cursor += it.size }
        val trail2 = silence(30 * frameSamples)
        val raw = lead1 + speech1 + gap + speech2 + trail2

        // Single continuous ground-truth pass (one VadGate instance, matching the pipeline's one
        // gate per collection): sum forwarded samples between SpeechEnded events, and separately
        // the grand total ignoring those boundaries, to compute both the correctly-flushed chunk
        // count and what the (buggy) merged-without-flush count would have been.
        val gate = VadGate()
        var offset = 0
        var sinceLastFlush = 0L
        var overallTotal = 0L
        var flushedChunkCount = 0
        while (offset + frameSamples <= raw.size) {
            val frame = raw.copyOfRange(offset, offset + frameSamples)
            offset += frameSamples
            val result = gate.process(frame)
            for (f in result.frames) {
                sinceLastFlush += f.size
                overallTotal += f.size
            }
            if (result.events.contains(VadGate.Event.SpeechEnded)) {
                flushedChunkCount += ceil(sinceLastFlush.toDouble() / chunkSamples).toInt()
                sinceLastFlush = 0
            }
        }
        flushedChunkCount += ceil(sinceLastFlush.toDouble() / chunkSamples).toInt() // any trailing remainder
        val mergedWithoutFlushChunkCount = ceil(overallTotal.toDouble() / chunkSamples).toInt()

        val chunks = AudioPipeline(FakeAudioCapture(raw)).chunks.toList()
        val actualChunkCount = chunks.size

        assertEquals(
            "expected per-utterance tail flush to produce $flushedChunkCount chunks",
            flushedChunkCount,
            actualChunkCount,
        )
        assertTrue(
            "flushed count should exceed the merged-without-flush count (otherwise this test " +
                "can't distinguish the bug from the fix)",
            flushedChunkCount > mergedWithoutFlushChunkCount,
        )
    }

    @Test
    fun `a late subscriber to vadEvents still observes the most recent VAD transition via replay`() = runTest {
        var cursor = 0
        val lead = silence(10 * frameSamples)
        val speech = tone(20 * frameSamples, cursor).also { cursor += it.size }
        val trail = silence(40 * frameSamples)
        val raw = lead + speech + trail

        val pipeline = AudioPipeline(FakeAudioCapture(raw))

        // Drive the whole pipeline to completion (drains capture.frames, which fires both
        // SpeechStarted and SpeechEnded into vadEvents) before anyone has subscribed to vadEvents.
        pipeline.chunks.toList()

        // A subscriber arriving only now must still see the latest event via the replay buffer,
        // not hang waiting for a new one that will never come (the source has already stopped).
        val latest = pipeline.vadEvents.first()
        assertEquals(VadGate.Event.SpeechEnded, latest)
    }
}
