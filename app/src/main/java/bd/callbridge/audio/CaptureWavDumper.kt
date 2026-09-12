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
 */
class CaptureWavDumper(
    private val context: Context,
    private val sampleRateHz: Int = 8000,
) {
    private var raf: RandomAccessFile? = null
    private var dataBytesWritten: Long = 0

    val isActive: Boolean get() = raf != null

    /** Opens a new WAV file and writes a placeholder header. Idempotent while already active. */
    fun start() {
        if (raf != null) return
        try {
            val dir = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.wav")
            val out = RandomAccessFile(file, "rw")
            writeHeader(out, dataLength = 0)
            raf = out
            dataBytesWritten = 0
            Log.i(TAG, "capture WAV dump started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to open capture WAV file", e)
            raf = null
        }
    }

    /** Appends mono PCM16 samples (little-endian) to the open file. No-op if not started. */
    fun write(pcm: ShortArray) {
        val out = raf ?: return
        try {
            val bytes = ByteArray(pcm.size * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            out.write(bytes)
            dataBytesWritten += bytes.size
        } catch (e: Exception) {
            Log.e(TAG, "failed to write capture WAV data", e)
        }
    }

    /** Finalizes the header with the real data length and closes the file. */
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
        }
    }

    private fun writeHeader(out: RandomAccessFile, dataLength: Long) {
        val byteRate = sampleRateHz * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
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
    }
}
