package bd.callbridge.audio

import kotlinx.coroutines.flow.Flow

/**
 * Captures call audio for the M1a milestone (spec §4.2): `AudioRecord(VOICE_DOWNLINK/VOICE_CALL)`
 * at 8 kHz, resampled to 16 kHz mono PCM16 frames for the Gemini bridge.
 *
 * Concrete implementation: [VoiceCallCapture]. VAD gating lives separately in [VadGate] (applied
 * downstream by [AudioPipeline]), not inside this interface, so it stays testable in isolation.
 */
interface AudioCapture {
    /** True while actively capturing. */
    val isCapturing: Boolean

    /** Starts capture. Frames are emitted on [frames] until [stop] is called. */
    fun start()

    fun stop()

    /** 16 kHz mono PCM16 frames, most recent last. */
    val frames: Flow<ShortArray>
}
