package bd.callbridge.audio

import android.util.Log
import kotlin.math.sqrt

/**
 * Pure-Kotlin energy-based voice activity gate (spec §4.2/§8). Operates on fixed-size frames
 * (default 20 ms @ 16 kHz = 320 samples). Buffers a pre-roll ring of silence so speech onset
 * includes the audio just before it was detected, and holds the gate open for a while after
 * speech appears to end, per spec §4.2 timing (200 ms pre-roll, 400 ms hold).
 *
 * The voice/silence decision is RMS energy above an **adaptive** threshold: a decaying
 * min-tracker follows the ambient noise floor (drops instantly to a quieter frame, rises slowly
 * otherwise so a burst of speech doesn't drag the floor up mid-utterance), and the working
 * threshold is `noiseFloor + energyMargin`. Onset additionally requires [onsetFrames] consecutive
 * voiced frames (hysteresis) so a single noisy frame can't fire a spurious [Event.SpeechStarted];
 * offset still uses [holdFrames] of trailing silence as before, and the hold timer resets to zero
 * the moment voice reappears mid-hold.
 *
 * This is a simple energy/RMS gate rather than a WebRTC VAD port — plenty for cutting silence
 * on rural calls, and trivial to swap out behind this same API if it proves too coarse.
 */
class VadGate(
    /** Frame size in samples. Default: 20 ms @ 16 kHz. */
    val frameSamples: Int = 320,
    /** Margin above the adaptive noise floor (both normalized to PCM16 full scale, 0.0-1.0)
     *  a frame's RMS must clear to be considered voice. */
    val energyMargin: Double = DEFAULT_ENERGY_MARGIN,
    /** Floor under the working threshold itself, so near-silence noise doesn't let the adaptive
     *  floor collapse the threshold to ~0 and start triggering on tiny bumps. */
    val minThreshold: Double = DEFAULT_MIN_THRESHOLD,
    /** Consecutive voiced frames required before declaring [Event.SpeechStarted] (onset
     *  hysteresis). The frames seen during the run are still forwarded once onset fires (via
     *  pre-roll), so this only delays the *event*, not audio delivery. */
    val onsetFrames: Int = DEFAULT_ONSET_FRAMES,
    /** Frames of silence to keep buffered before speech onset, emitted once speech starts. */
    val preRollFrames: Int = 10,
    /** Frames of continued silence to keep gating open after the last voice frame, before
     *  declaring speech ended. */
    val holdFrames: Int = 20,
    /** How slowly the noise floor rises back up per silent frame once it has dropped (fraction
     *  of the gap to the current frame RMS closed per frame). Kept independent of [energyMargin]
     *  so tuning one doesn't require re-tuning the other. */
    val noiseFloorRiseRate: Double = DEFAULT_NOISE_FLOOR_RISE_RATE,
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
    private var consecutiveVoiced = 0
    private var noiseFloor = 0.0

    private var lastRmsLogNanos = 0L

    private fun rms(frame: ShortArray): Double {
        var sumSq = 0.0
        for (s in frame) {
            val n = s.toDouble() / 32768.0
            sumSq += n * n
        }
        return sqrt(sumSq / frame.size)
    }

    /** Updates the adaptive noise floor from this frame's RMS: drops immediately to a quieter
     *  frame, otherwise rises slowly toward it. Only called for frames not already gated open as
     *  speech, so a long utterance can't drag the floor up while it's happening. */
    private fun updateNoiseFloor(frameRms: Double) {
        noiseFloor = if (frameRms < noiseFloor) {
            frameRms
        } else {
            noiseFloor + (frameRms - noiseFloor) * noiseFloorRiseRate
        }
    }

    private fun threshold(): Double = maxOf(minThreshold, noiseFloor + energyMargin)

    private fun logRmsRateLimited(frameRms: Double, thresholdUsed: Double, voice: Boolean) {
        val now = System.nanoTime()
        if (now - lastRmsLogNanos >= RMS_LOG_INTERVAL_NANOS) {
            lastRmsLogNanos = now
            Log.d(TAG, "rms=%.4f floor=%.4f threshold=%.4f voice=%s".format(frameRms, noiseFloor, thresholdUsed, voice))
        }
    }

    /** Feeds one [frameSamples]-sized frame through the gate. */
    fun process(frame: ShortArray): Result {
        require(frame.size == frameSamples) {
            "expected $frameSamples samples per frame, got ${frame.size}"
        }
        val frameRms = rms(frame)
        val thresholdUsed = threshold()
        val voice = frameRms >= thresholdUsed
        if (!inSpeech) updateNoiseFloor(frameRms)
        logRmsRateLimited(frameRms, thresholdUsed, voice)

        val outFrames = mutableListOf<ShortArray>()
        val events = mutableListOf<Event>()

        if (!inSpeech) {
            if (voice) {
                consecutiveVoiced++
                preRoll.addLast(frame)
                while (preRoll.size > preRollFrames) preRoll.removeFirst()
                if (consecutiveVoiced >= onsetFrames) {
                    inSpeech = true
                    silenceRun = 0
                    consecutiveVoiced = 0
                    events += Event.SpeechStarted
                    outFrames += preRoll.toList()
                    preRoll.clear()
                }
            } else {
                consecutiveVoiced = 0
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

    /** Resets to the initial (silent, empty pre-roll, unlearned noise floor) state. */
    fun reset() {
        preRoll.clear()
        inSpeech = false
        silenceRun = 0
        consecutiveVoiced = 0
        noiseFloor = 0.0
        lastRmsLogNanos = 0L
    }

    companion object {
        private const val TAG = "VadGate"

        const val DEFAULT_ENERGY_MARGIN: Double = 0.02
        const val DEFAULT_MIN_THRESHOLD: Double = 0.01
        const val DEFAULT_ONSET_FRAMES: Int = 3
        const val DEFAULT_NOISE_FLOOR_RISE_RATE: Double = 0.01

        private const val RMS_LOG_INTERVAL_NANOS = 5_000_000_000L
    }
}
