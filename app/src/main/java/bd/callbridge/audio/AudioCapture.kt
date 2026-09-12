package bd.callbridge.audio

import kotlinx.coroutines.flow.Flow

/**
 * Captures call audio for the M1a milestone (spec §4.2): `AudioRecord(VOICE_CALL)` at 8 kHz,
 * resampled to 16 kHz mono PCM16 frames for the Gemini bridge.
 *
 * HANDOFF (audio-pipeline worker): implement a concrete `TelephonyAudioCapture` that wraps
 * `android.media.AudioRecord` with `MediaRecorder.AudioSource.VOICE_CALL`, requires
 * `CAPTURE_AUDIO_OUTPUT` (priv-app only), and emits [Config.CAPTURE_SAMPLE_RATE_HZ] (16 kHz)
 * mono PCM16 frames on [frames] after resampling from the 8 kHz source. Add VAD gating
 * (WebRTC VAD mode 2, spec §4.2) as a decorator or a separate `VadGate` class, not inside this
 * interface, so it stays testable in isolation.
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
