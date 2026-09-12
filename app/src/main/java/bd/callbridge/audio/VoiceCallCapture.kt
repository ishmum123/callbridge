package bd.callbridge.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import bd.callbridge.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Concrete [AudioCapture] for M1a (spec §4.2): opens `AudioRecord` on the call's downlink at
 * 8 kHz mono PCM16, reads on a dedicated background thread, and resamples to
 * [Config.CAPTURE_SAMPLE_RATE_HZ] (16 kHz) before emitting on [frames].
 *
 * Tries [MediaRecorder.AudioSource.VOICE_DOWNLINK] first (caller's voice only, per
 * docs/hal-recon.md — this device's `mixer_paths.xml`/`audio_platform_info.xml` expose a
 * dedicated downlink-only usecase), falling back to [MediaRecorder.AudioSource.VOICE_CALL]
 * (mixed uplink+downlink) if the downlink-only source fails to initialize. Both require
 * `CAPTURE_AUDIO_OUTPUT` (signature|privileged) — this only works from the Magisk-installed
 * priv-app build (spec §5), not a plain debug install. `AudioRecord` init failure (missing
 * permission, source unsupported, wrong build) is reported via [isCapturing] staying false and
 * a logged error, not a crash.
 *
 * [start]/[stop] are idempotent.
 */
class VoiceCallCapture(
    private val context: Context,
    private val preferredSource: Int = MediaRecorder.AudioSource.VOICE_DOWNLINK,
    private val fallbackSource: Int = MediaRecorder.AudioSource.VOICE_CALL,
    private val outputSampleRateHz: Int = Config.CAPTURE_SAMPLE_RATE_HZ,
    /** Optional raw-8kHz WAV dump for the M1a "listen to it" deliverable. */
    private val wavDumper: CaptureWavDumper? = null,
) : AudioCapture {

    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    override val isCapturing: Boolean get() = running

    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    override val frames: Flow<ShortArray> = _frames.asSharedFlow()

    override fun start() {
        if (running) return

        val minBufferBytes = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed ($minBufferBytes) for $CAPTURE_SAMPLE_RATE_HZ Hz")
            return
        }
        val bufferSizeBytes = minBufferBytes * 4

        var record = tryOpen(preferredSource, bufferSizeBytes)
        var sourceUsed = preferredSource
        if (record == null) {
            record = tryOpen(fallbackSource, bufferSizeBytes)
            sourceUsed = fallbackSource
        }
        if (record == null) {
            Log.e(TAG, "AudioRecord init failed for both preferred and fallback sources; not capturing")
            return
        }

        audioRecord = record
        running = true
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording failed", e)
            running = false
            record.release()
            audioRecord = null
            return
        }
        wavDumper?.start()
        Log.i(TAG, "capture started (source=$sourceUsed, bufferBytes=$bufferSizeBytes)")

        val resampler = Resampler(CAPTURE_SAMPLE_RATE_HZ, outputSampleRateHz)
        val readBuf = ShortArray(bufferSizeBytes / 2)
        readThread = Thread({
            while (running) {
                val n = try {
                    record.read(readBuf, 0, readBuf.size)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord.read threw", e)
                    break
                }
                if (n > 0) {
                    val chunk = readBuf.copyOf(n)
                    wavDumper?.write(chunk)
                    val resampled = resampler.process(chunk)
                    if (resampled.isNotEmpty()) _frames.tryEmit(resampled)
                } else if (n < 0) {
                    Log.e(TAG, "AudioRecord.read returned error code $n")
                    break
                }
            }
        }, "VoiceCallCapture-read").apply { start() }
    }

    override fun stop() {
        if (!running) return
        running = false
        readThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        readThread = null
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // Not recording; nothing to stop.
            }
            record.release()
        }
        audioRecord = null
        wavDumper?.stop()
        Log.i(TAG, "capture stopped")
    }

    private fun tryOpen(source: Int, bufferSizeBytes: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                source,
                CAPTURE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord denied for source=$source (missing CAPTURE_AUDIO_OUTPUT?)", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord unsupported for source=$source on this device", e)
            null
        }
    }

    companion object {
        private const val TAG = "VoiceCallCapture"
        private const val CAPTURE_SAMPLE_RATE_HZ = 8000
    }
}
