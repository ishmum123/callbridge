package bd.callbridge.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import bd.callbridge.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * permission, source unsupported, wrong build) is reported via [state] going to
 * [CaptureState.Failed] (and [isCapturing] staying false), not a crash.
 *
 * [start]/[stop] are `@Synchronized` and idempotent.
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

    /** Set by [stop] before unblocking the read thread, so the thread's own cleanup can tell a
     *  requested stop apart from an unexpected read failure/thread death. */
    @Volatile
    private var stopRequested = false

    override val isCapturing: Boolean get() = running

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    override val frames: Flow<ShortArray> = _frames.asSharedFlow()

    @Synchronized
    override fun start() {
        if (running) return
        stopRequested = false

        val minBufferBytes = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            val reason = "AudioRecord.getMinBufferSize failed ($minBufferBytes) for $CAPTURE_SAMPLE_RATE_HZ Hz"
            Log.e(TAG, reason)
            _state.value = CaptureState.Failed(reason)
            return
        }
        // AudioRecord's internal ring stays generous (avoids overruns between our reads), but we
        // read it in small fixed-size pieces below rather than draining the whole ring at once,
        // to keep latency and worst-case overrun risk down.
        val bufferSizeBytes = minBufferBytes * 4

        var record = tryOpen(preferredSource, bufferSizeBytes)
        var sourceUsed = preferredSource
        if (record == null) {
            record = tryOpen(fallbackSource, bufferSizeBytes)
            sourceUsed = fallbackSource
        }
        if (record == null) {
            val reason = "AudioRecord init failed for both preferred ($preferredSource) and fallback ($fallbackSource) sources"
            Log.e(TAG, "$reason; not capturing")
            _state.value = CaptureState.Failed(reason)
            return
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            val reason = "AudioRecord.startRecording failed: ${e.message}"
            Log.e(TAG, reason, e)
            _state.value = CaptureState.Failed(reason)
            record.release()
            audioRecord = null
            return
        }
        running = true
        _state.value = CaptureState.Running
        wavDumper?.start()
        Log.i(TAG, "capture started (source=$sourceUsed, bufferBytes=$bufferSizeBytes)")

        val resampler = Resampler(CAPTURE_SAMPLE_RATE_HZ, outputSampleRateHz)
        val readChunkSamples = (CAPTURE_SAMPLE_RATE_HZ * READ_CHUNK_MS / 1000)
        val readBuf = ShortArray(readChunkSamples)
        readThread = Thread({
            var dropCount = 0L
            var lastLogNanos = System.nanoTime()
            try {
                while (running) {
                    val n = try {
                        record.read(readBuf, 0, readBuf.size)
                    } catch (e: Exception) {
                        val reason = "AudioRecord.read threw: ${e.message}"
                        Log.e(TAG, reason, e)
                        _state.value = CaptureState.Failed(reason)
                        break
                    }
                    if (n > 0) {
                        val chunk = readBuf.copyOf(n)
                        wavDumper?.write(chunk)
                        val resampled = resampler.process(chunk)
                        if (resampled.isNotEmpty() && !_frames.tryEmit(resampled)) {
                            dropCount++
                        }
                        val now = System.nanoTime()
                        if (now - lastLogNanos >= DROP_LOG_INTERVAL_NANOS) {
                            if (dropCount > 0) {
                                Log.w(TAG, "dropped $dropCount frame emissions in the last ~${DROP_LOG_INTERVAL_NANOS / 1_000_000_000}s (frames has no/slow collector)")
                            }
                            dropCount = 0
                            lastLogNanos = now
                        }
                    } else if (n < 0) {
                        val reason = "AudioRecord.read returned error code $n"
                        Log.e(TAG, reason)
                        _state.value = CaptureState.Failed(reason)
                        break
                    }
                }
            } finally {
                // Whatever caused this loop to exit (normal stop(), a read error above, or the
                // AudioRecord going bad), the capture is no longer running. Without this, a
                // read-thread death that skipped the `break`'s Failed assignment (shouldn't
                // happen given the two branches above, but keep this as the source of truth)
                // would otherwise leave `running` stuck true forever, wedging start()/stop().
                running = false
                if (!stopRequested && _state.value !is CaptureState.Failed) {
                    _state.value = CaptureState.Failed("capture read thread exited unexpectedly")
                }
            }
        }, "VoiceCallCapture-read").apply { start() }
    }

    @Synchronized
    override fun stop() {
        stopRequested = true
        val record = audioRecord
        val thread = readThread
        if (record == null && thread == null) {
            running = false
            return
        }
        running = false

        // Stop the AudioRecord BEFORE joining: a read thread parked in AudioRecord.read() (the
        // normal state at call teardown) only returns once the record is stopped. Joining first
        // would block here while the thread blocks in the HAL, and a naive short-timeout join
        // followed by release() would let this thread free the native AudioRecord out from under
        // a read() that's still in flight -> native use-after-free / SIGSEGV.
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Not recording; nothing to stop.
            }
        }

        thread?.let {
            try {
                it.join(STOP_JOIN_TIMEOUT_MS)
                if (it.isAlive) {
                    Log.e(TAG, "read thread did not exit within ${STOP_JOIN_TIMEOUT_MS}ms of AudioRecord.stop(); leaking it and the AudioRecord rather than risking a use-after-free")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (thread == null || !thread.isAlive) {
            // Only release/null once we're sure nothing can still be touching it.
            readThread = null
            record?.release()
            audioRecord = null
        }

        wavDumper?.stop()
        if (_state.value !is CaptureState.Failed) _state.value = CaptureState.Idle
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

        /** Read granularity: fixed 20 ms pieces rather than draining the whole (4x-min) ring in
         *  one read, to keep latency and overrun risk down (spec §4.2). */
        private const val READ_CHUNK_MS = 20

        /** How long [stop] waits for the read thread to notice [AudioRecord.stop] and exit
         *  before giving up and leaking rather than releasing out from under it. */
        private const val STOP_JOIN_TIMEOUT_MS = 5000L

        private const val DROP_LOG_INTERVAL_NANOS = 5_000_000_000L
    }
}
