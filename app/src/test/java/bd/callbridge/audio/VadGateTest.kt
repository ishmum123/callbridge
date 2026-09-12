package bd.callbridge.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadGateTest {

    private val frameSamples = 320 // 20 ms @ 16 kHz

    private fun silenceFrame(): ShortArray = ShortArray(frameSamples) { 0 }

    private fun toneFrame(startSample: Int, freqHz: Double = 1000.0, rate: Int = 16000, amplitude: Double = 12000.0): ShortArray =
        ShortArray(frameSamples) { i ->
            (amplitude * sin(2.0 * PI * freqHz * (startSample + i) / rate)).toInt().toShort()
        }

    @Test
    fun `silence, tone, silence fires each event exactly once with correct pre-roll and hold timing`() {
        val preRollFrames = 10 // 200 ms
        val holdFrames = 20 // 400 ms
        val gate = VadGate(frameSamples = frameSamples, energyThreshold = 0.05, preRollFrames = preRollFrames, holdFrames = holdFrames)

        val silenceLeadFrames = 30
        val toneFrames = 40
        val silenceTrailFrames = 40

        data class Occurrence(val frameIndex: Int, val event: VadGate.Event, val emittedCount: Int)

        val occurrences = mutableListOf<Occurrence>()
        var speechStartedFrameIndex = -1
        var speechStartedEmittedCount = -1
        var speechEndedFrameIndex = -1

        var sampleCursor = 0
        var frameIndex = 0

        repeat(silenceLeadFrames) {
            val result = gate.process(silenceFrame())
            result.events.forEach { occurrences += Occurrence(frameIndex, it, result.frames.size) }
            frameIndex++
        }
        repeat(toneFrames) {
            val frame = toneFrame(sampleCursor)
            sampleCursor += frameSamples
            val result = gate.process(frame)
            result.events.forEach { occurrences += Occurrence(frameIndex, it, result.frames.size) }
            if (result.events.contains(VadGate.Event.SpeechStarted)) {
                speechStartedFrameIndex = frameIndex
                speechStartedEmittedCount = result.frames.size
            }
            frameIndex++
        }
        repeat(silenceTrailFrames) {
            val result = gate.process(silenceFrame())
            result.events.forEach { occurrences += Occurrence(frameIndex, it, result.frames.size) }
            if (result.events.contains(VadGate.Event.SpeechEnded)) {
                speechEndedFrameIndex = frameIndex
            }
            frameIndex++
        }

        val starts = occurrences.filter { it.event == VadGate.Event.SpeechStarted }
        val ends = occurrences.filter { it.event == VadGate.Event.SpeechEnded }
        assertEquals("SpeechStarted should fire exactly once", 1, starts.size)
        assertEquals("SpeechEnded should fire exactly once", 1, ends.size)

        // Speech starts exactly at the first tone frame (index silenceLeadFrames).
        assertEquals(silenceLeadFrames, speechStartedFrameIndex)

        // Pre-roll: the frame that carries SpeechStarted should flush ~preRollFrames buffered
        // silence frames plus the current voiced frame (±1 frame tolerance).
        assertTrue(
            "expected ~${preRollFrames + 1} frames flushed on speech start, got $speechStartedEmittedCount",
            abs(speechStartedEmittedCount - (preRollFrames + 1)) <= 1,
        )

        // Hold: SpeechEnded should fire ~holdFrames after the last voiced frame
        // (last tone frame index = silenceLeadFrames + toneFrames - 1).
        val lastToneFrameIndex = silenceLeadFrames + toneFrames - 1
        val framesAfterToneEnded = speechEndedFrameIndex - lastToneFrameIndex
        assertTrue(
            "expected SpeechEnded ~${holdFrames + 1} frames after last tone frame, got $framesAfterToneEnded",
            abs(framesAfterToneEnded - (holdFrames + 1)) <= 1,
        )
    }

    @Test
    fun `pure silence never triggers speech events`() {
        val gate = VadGate(frameSamples = frameSamples)
        repeat(100) {
            val result = gate.process(silenceFrame())
            assertTrue(result.events.isEmpty())
            assertTrue(result.frames.isEmpty())
        }
    }

    @Test
    fun `rejects frames of the wrong size`() {
        val gate = VadGate(frameSamples = frameSamples)
        try {
            gate.process(ShortArray(100))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
