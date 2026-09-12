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

    private fun newGate(
        energyMargin: Double = 0.05,
        preRollFrames: Int = 10,
        holdFrames: Int = 20,
        onsetFrames: Int = 3,
    ) = VadGate(
        frameSamples = frameSamples,
        energyMargin = energyMargin,
        preRollFrames = preRollFrames,
        holdFrames = holdFrames,
        onsetFrames = onsetFrames,
    )

    @Test
    fun `silence, tone, silence fires each event exactly once with correct onset and hold timing`() {
        val preRollFrames = 10 // 200 ms
        val holdFrames = 20 // 400 ms
        val onsetFrames = 3
        val gate = newGate(preRollFrames = preRollFrames, holdFrames = holdFrames, onsetFrames = onsetFrames)

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

        // Onset hysteresis: SpeechStarted fires on the (onsetFrames)th consecutive voiced frame,
        // i.e. at index (first tone frame) + onsetFrames - 1.
        val expectedStartIndex = silenceLeadFrames + onsetFrames - 1
        assertEquals(expectedStartIndex, speechStartedFrameIndex)

        // Pre-roll: the frame that carries SpeechStarted flushes the last preRollFrames buffered
        // frames (a mix of trailing silence and the onset run itself), since there was ample
        // leading silence to fill the whole pre-roll window.
        assertEquals(preRollFrames, speechStartedEmittedCount)

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
        val gate = newGate()
        repeat(100) {
            val result = gate.process(silenceFrame())
            assertTrue(result.events.isEmpty())
            assertTrue(result.frames.isEmpty())
        }
    }

    @Test
    fun `rejects frames of the wrong size`() {
        val gate = newGate()
        try {
            gate.process(ShortArray(100))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `speech starting in the very first frames with empty pre-roll still fires SpeechStarted`() {
        val onsetFrames = 3
        val gate = newGate(onsetFrames = onsetFrames)
        var sampleCursor = 0
        var started = false
        var emittedOnStart = -1
        repeat(10) { i ->
            val frame = toneFrame(sampleCursor)
            sampleCursor += frameSamples
            val result = gate.process(frame)
            if (result.events.contains(VadGate.Event.SpeechStarted)) {
                assertTrue("SpeechStarted should fire only once", !started)
                started = true
                emittedOnStart = result.frames.size
                assertEquals("should fire on the ${onsetFrames}rd consecutive voiced frame", onsetFrames - 1, i)
            }
        }
        assertTrue("expected SpeechStarted to fire even with no pre-roll silence buffered", started)
        // No leading silence was buffered, so exactly onsetFrames voiced frames are flushed.
        assertEquals(onsetFrames, emittedOnStart)
    }

    @Test
    fun `hold timer resets when voice returns mid-hold, delaying SpeechEnded`() {
        val holdFrames = 20
        val gate = newGate(holdFrames = holdFrames)
        var sampleCursor = 0
        fun tone(): ShortArray {
            val f = toneFrame(sampleCursor)
            sampleCursor += frameSamples
            return f
        }

        // Get into speech.
        repeat(3) { gate.process(tone()) }

        // Silence for less than the hold window, then a voiced frame resets the hold timer.
        repeat(holdFrames - 5) { gate.process(silenceFrame()) }
        val resetResult = gate.process(tone())
        assertTrue("a voiced frame mid-hold should not itself end speech", resetResult.events.isEmpty())

        // Now run only (holdFrames - 5) more silent frames: if the timer had NOT reset, this
        // would already be well past the original hold deadline and SpeechEnded would have fired.
        var endedEarly = false
        repeat(holdFrames - 5) {
            val r = gate.process(silenceFrame())
            if (r.events.contains(VadGate.Event.SpeechEnded)) endedEarly = true
        }
        assertTrue("hold timer should have reset on the mid-hold voiced frame", !endedEarly)

        // Running out the full hold window from the reset point does end speech.
        var endedAfterFullHold = false
        repeat(10) {
            val r = gate.process(silenceFrame())
            if (r.events.contains(VadGate.Event.SpeechEnded)) endedAfterFullHold = true
        }
        assertTrue("SpeechEnded should eventually fire once the (reset) hold window elapses", endedAfterFullHold)
    }

    @Test
    fun `two consecutive utterances produce two distinct start-end pairs`() {
        val gate = newGate(holdFrames = 5, onsetFrames = 2)
        var sampleCursor = 0
        fun tone(): ShortArray {
            val f = toneFrame(sampleCursor)
            sampleCursor += frameSamples
            return f
        }

        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var frameIndex = 0
        fun run(n: Int, voiced: Boolean) {
            repeat(n) {
                val result = gate.process(if (voiced) tone() else silenceFrame())
                if (result.events.contains(VadGate.Event.SpeechStarted)) starts += frameIndex
                if (result.events.contains(VadGate.Event.SpeechEnded)) ends += frameIndex
                frameIndex++
            }
        }

        run(10, voiced = false) // lead silence
        run(15, voiced = true) // utterance 1
        run(20, voiced = false) // enough silence to end utterance 1 and settle
        run(15, voiced = true) // utterance 2
        run(20, voiced = false) // end utterance 2

        assertEquals("expected two distinct SpeechStarted events", 2, starts.size)
        assertEquals("expected two distinct SpeechEnded events", 2, ends.size)
        assertTrue("second utterance should start after the first one ended", starts[1] > ends[0])
    }

    @Test
    fun `default constructor thresholds classify silence and normal speech level correctly`() {
        val gate = VadGate(frameSamples = frameSamples)
        // Pure silence: never voice, regardless of the (initially zero) adaptive floor.
        repeat(5) {
            assertTrue(gate.process(silenceFrame()).frames.isEmpty())
        }
        // A clearly-above-noise-floor tone should cross the default threshold and eventually
        // fire SpeechStarted within the default onset window.
        var sampleCursor = 0
        var started = false
        repeat(VadGate.DEFAULT_ONSET_FRAMES + 2) {
            val frame = toneFrame(sampleCursor, amplitude = 12000.0)
            sampleCursor += frameSamples
            if (gate.process(frame).events.contains(VadGate.Event.SpeechStarted)) started = true
        }
        assertTrue("default-threshold gate should detect a normal speech-level tone", started)
    }
}
