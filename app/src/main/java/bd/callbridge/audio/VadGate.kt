package bd.callbridge.audio

import kotlin.math.sqrt

/**
 * Pure-Kotlin energy-based voice activity gate (spec §4.2/§8). Operates on fixed-size frames
 * (default 20 ms @ 16 kHz = 320 samples). Buffers a pre-roll ring of silence so speech onset
 * includes the audio just before it was detected, and holds the gate open for a while after
 * speech appears to end, per spec §4.2 timing (200 ms pre-roll, 400 ms hold).
 *
 * This is a simple energy/RMS gate rather than a WebRTC VAD port — plenty for cutting silence
 * on rural calls, and trivial to swap out behind this same API if it proves too coarse.
 */
class VadGate(
    /** Frame size in samples. Default: 20 ms @ 16 kHz. */
    val frameSamples: Int = 320,
    /** RMS energy threshold, normalized to PCM16 full scale (0.0-1.0), above which a frame is
     *  considered voice. */
    val energyThreshold: Double = 0.02,
    /** Frames of silence to keep buffered before speech onset, emitted once speech starts. */
    val preRollFrames: Int = 10,
    /** Frames of continued silence to keep gating open after the last voice frame, before
     *  declaring speech ended. */
    val holdFrames: Int = 20,
) {

    sealed class Event {
        object SpeechStarted : Event()
        object SpeechEnded : Event()
    }

    /** [frames]: zero or more frames (in original [frameSamples]-sized pieces) to forward
     *  downstream for this input frame. [events]: zero or more VAD transitions triggered by
     *  this input frame (each event type fires at most once per transition). */
    data class Result(val frames: List<ShortArray>, val events: List<Event>)

    private val preRoll = ArrayDeque<ShortArray>()
    private var inSpeech = false
    private var silenceRun = 0

    private fun rms(frame: ShortArray): Double {
        var sumSq = 0.0
        for (s in frame) {
            val n = s.toDouble() / 32768.0
            sumSq += n * n
        }
        return sqrt(sumSq / frame.size)
    }

    private fun isVoice(frame: ShortArray): Boolean = rms(frame) >= energyThreshold

    /** Feeds one [frameSamples]-sized frame through the gate. */
    fun process(frame: ShortArray): Result {
        require(frame.size == frameSamples) {
            "expected $frameSamples samples per frame, got ${frame.size}"
        }
        val voice = isVoice(frame)
        val outFrames = mutableListOf<ShortArray>()
        val events = mutableListOf<Event>()

        if (!inSpeech) {
            if (voice) {
                inSpeech = true
                silenceRun = 0
                events += Event.SpeechStarted
                outFrames += preRoll.toList()
                preRoll.clear()
                outFrames += frame
            } else {
                preRoll.addLast(frame)
                while (preRoll.size > preRollFrames) preRoll.removeFirst()
            }
        } else {
            if (voice) {
                silenceRun = 0
                outFrames += frame
            } else {
                silenceRun++
                if (silenceRun <= holdFrames) {
                    outFrames += frame
                } else {
                    inSpeech = false
                    events += Event.SpeechEnded
                    preRoll.clear()
                    preRoll.addLast(frame)
                }
            }
        }
        return Result(outFrames, events)
    }

    /** Resets to the initial (silent, empty pre-roll) state. */
    fun reset() {
        preRoll.clear()
        inSpeech = false
        silenceRun = 0
    }
}
