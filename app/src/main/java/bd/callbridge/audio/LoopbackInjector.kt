package bd.callbridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Route C (spec §4.3): plays straight to the device's default speaker/media output. Used only
 * with the physical padded-box loopback rig (call on speakerphone, a second phone/laptop's mic
 * feeds the Gemini bridge) — this class does no call routing at all, it is just a normal media
 * `AudioTrack`. Always works; lowest quality; last-resort fallback per spec §4.3/§7 M1b, used
 * only if both Route B and Route A fail (Route A never succeeds on this OS version, see
 * [IncallMusicInjector], so in practice this is the fallback for Route B failing).
 */
class LoopbackInjector(@Suppress("UNUSED_PARAMETER") context: Context) : Injector {
    override val route: InjectorRoute = InjectorRoute.LOOPBACK

    override val openSampleRateHz: Int?
        get() = lock.withLock { if (track != null) SAMPLE_RATE_HZ else null }

    // See TelephonyTxInjector's [lock]/[closed] doc: same write-vs-release race category
    // (write()/flush() on one thread, close() from BridgeSession.stop() on another), same fix.
    private val lock = ReentrantLock()
    private var track: AudioTrack? = null
    @Volatile private var closed = false
    private var lastDetail = "not opened yet"

    override fun open() = lock.withLock {
        if (closed || track != null) return
        track = buildTrack().also {
            if (it == null) {
                lastDetail = "AudioTrack construction failed at $SAMPLE_RATE_HZ Hz/mono"
                Log.w(TAG, lastDetail)
            } else {
                lastDetail = "playing to default speaker/media output (no call routing)"
                it.play()
            }
        }
    }

    private fun buildTrack(): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return null
            val newTrack = AudioTrack(
                attrs,
                format,
                minBuf * BUFFER_SIZE_MULTIPLIER,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                newTrack.release()
                return null
            }
            newTrack
        } catch (e: Exception) {
            Log.w(TAG, "buildTrack() threw", e)
            null
        }
    }

    override fun write(pcm: ShortArray) = lock.withLock {
        if (closed) return
        val activeTrack = track ?: return
        activeTrack.write(pcm, 0, pcm.size)
        Unit
    }

    override fun flush() = lock.withLock {
        if (closed) return
        val activeTrack = track ?: return
        activeTrack.pause()
        activeTrack.flush()
        activeTrack.play()
    }

    override fun close() = lock.withLock {
        if (closed) return
        closed = true
        track?.let {
            it.stop()
            it.release()
        }
        track = null
    }

    override fun probe(): RouteProbe = lock.withLock {
        RouteProbe(
            route = route,
            deviceFound = true,
            preferredDeviceSet = true,
            routedDeviceId = track?.routedDevice?.id,
            detail = lastDetail,
        )
    }

    companion object {
        private const val TAG = "LoopbackInjector"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }
}
