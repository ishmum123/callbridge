package bd.callbridge.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Captures call audio for the M1a milestone (spec §4.2): `AudioRecord(VOICE_DOWNLINK/VOICE_CALL)`
 * at 8 kHz, resampled to 16 kHz mono PCM16 frames for the Gemini bridge.
 *
 * Concrete implementation: [VoiceCallCapture]. VAD gating lives separately in [VadGate] (applied
 * downstream by [AudioPipeline]), not inside this interface, so it stays testable in isolation.
 */
interface AudioCapture {
    /** True while actively capturing. Equivalent to `state.value is CaptureState.Running`. */
    val isCapturing: Boolean

    /** Current lifecycle/error state. A status screen should observe this rather than poll
     *  [isCapturing], since it's the only way to learn *why* capture stopped (init failure vs.
     *  the read thread dying vs. a normal [stop] call). */
    val state: StateFlow<CaptureState>

    /** Starts capture. Frames are emitted on [frames] until [stop] is called or capture fails. */
    fun start()

    fun stop()

    /** 16 kHz mono PCM16 frames, most recent last. Emission is drop-not-block: a slow/absent
     *  collector never stalls the audio read thread (see implementation for buffer policy). */
    val frames: Flow<ShortArray>
}

/** Lifecycle/error state for an [AudioCapture]. */
sealed class CaptureState {
    /** Not capturing; no error. */
    object Idle : CaptureState()

    /** Actively capturing audio. */
    object Running : CaptureState()

    /** Capture stopped on its own (init failure, or the read thread died) rather than via
     *  [AudioCapture.stop]. [reason] is a short human-readable description for logs/UI. */
    data class Failed(val reason: String) : CaptureState()
}
