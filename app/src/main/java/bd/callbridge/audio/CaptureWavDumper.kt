package bd.callbridge.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M1a listening deliverable (spec §7): writes raw 8 kHz mono PCM16 capture audio to a WAV file
 * under the app's files dir (`captures/<timestamp>.wav`) so the raw GSM downlink audio can be
 * pulled off the phone and listened to, to judge quality and which side(s) of the call
 * [VoiceCallCapture] is actually receiving. Not wired into the default pipeline; pass an
 * instance to [VoiceCallCapture] to enable it.
 *
 * [start]/[write]/[stop] are `@Synchronized`: [write] runs on the capture read thread while
 * [start]/[stop] can be called from a caller/lifecycle thread, so all three need to agree on
 * `raf`'s state rather than just publishing it `@Volatile`.
 */
class CaptureWavDumper(
    private val context: Context,
    private val sampleRateHz: Int = 8000,
    /** Stop accepting writes once the file would exceed this size, to bound on-device storage
     *  use for what's meant to be a short debugging capture, not a full-call archive. */
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
) {
    private var raf: RandomAccessFile? = null
    private var dataBytesWritten: Long = 0
    private var cappedLogged = false

    @get:Synchronized
    val isActive: Boolean get() = raf != null

    /** Opens a new WAV file and writes a placeholder header. Idempotent while already active. */
    @Synchronized
    fun start() {
        if (raf != null) return
        try {
            val dir = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.wav")
            val out = RandomAccessFile(file, "rw")
            writeHeader(out, dataLength = 0)
            raf = out
            dataBytesWritten = 0
            cappedLogged = false
            Log.i(TAG, "capture WAV dump started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to open capture WAV file", e)
            raf = null
        }
    }

    /** Appends mono PCM16 samples (little-endian) to the open file. No-op if not started or once
     *  [maxFileSizeBytes] has been reached. */
    @Synchronized
    fun write(pcm: ShortArray) {
        val out = raf ?: return
        if (HEADER_BYTES + dataBytesWritten >= maxFileSizeBytes) {
            if (!cappedLogged) {
                Log.w(TAG, "capture WAV reached ${maxFileSizeBytes}B cap; dropping further writes")
                cappedLogged = true
            }
            return
        }
        try {
            val budget = (maxFileSizeBytes - HEADER_BYTES - dataBytesWritten).coerceAtLeast(0)
            val bytesToWrite = minOf((pcm.size * 2).toLong(), budget).toInt()
            val samplesToWrite = bytesToWrite / 2
            val bytes = ByteArray(samplesToWrite * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samplesToWrite) bb.putShort(pcm[i])
            out.write(bytes)
            dataBytesWritten += bytes.size
        } catch (e: Exception) {
            Log.e(TAG, "failed to write capture WAV data", e)
        }
    }

    /** Finalizes the header with the real data length and closes the file. */
    @Synchronized
    fun stop() {
        val out = raf ?: return
        try {
            writeHeader(out, dataBytesWritten)
            out.close()
        } catch (e: Exception) {
            Log.e(TAG, "failed to finalize capture WAV file", e)
        } finally {
            raf = null
            dataBytesWritten = 0
            cappedLogged = false
        }
    }

    private fun writeHeader(out: RandomAccessFile, dataLength: Long) {
        val byteRate = sampleRateHz * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataLength).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // PCM format
        header.putShort(CHANNELS.toShort())
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray())
        header.putInt(dataLength.toInt())
        out.seek(0)
        out.write(header.array())
    }

    companion object {
        private const val TAG = "CaptureWavDumper"
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val HEADER_BYTES = 44
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 50L * 1024 * 1024
    }
}
