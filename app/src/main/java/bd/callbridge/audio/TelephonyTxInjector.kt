package bd.callbridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Route B (spec §4.3): a plain `android.media.AudioTrack` routed to the `TELEPHONY_TX` output
 * device via `AudioTrack.setPreferredDevice()`. Needs `MODIFY_AUDIO_ROUTING` (declared as a
 * priv-app permission in the manifest, spec §5).
 *
 * This is the real primary route: per docs/hal-recon.md the `Telephony Tx` device port is
 * exposed and accepts PCM16 8000/16000 Hz, mono or stereo. Route A's `incall_music_uplink`
 * mixPort is stereo-only, so this class prefers 16 kHz/stereo first (one TestTone buffer works
 * unmodified for both routes) and falls back to 8 kHz/mono if track creation fails at the
 * preferred format. Spec's own build order is "Route B -> Route A -> Route C" (§4.3/§7 M1b) —
 * cheaper to verify than A, and per docs/hal-recon.md Route A is not implementable from an app
 * process anyway (see [IncallMusicInjector]), so in practice this is the path that matters.
 */
class TelephonyTxInjector(private val context: Context) : Injector {
    override val route: InjectorRoute = InjectorRoute.TELEPHONY_TX

    override val openSampleRateHz: Int?
        get() = track?.let { if (stereo) PREFERRED_SAMPLE_RATE_HZ else FALLBACK_SAMPLE_RATE_HZ }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var track: AudioTrack? = null
    private var stereo: Boolean = true

    private var lastDeviceFound = false
    private var lastPreferredSet = false
    private var lastDetail = "not opened yet"

    override fun open() {
        if (track != null) return

        val telephonyDevice = findTelephonyTxDevice()
        lastDeviceFound = telephonyDevice != null

        var created = buildTrack(PREFERRED_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_STEREO)
        stereo = created != null
        if (created == null) {
            created = buildTrack(FALLBACK_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO)
            stereo = false
        }
        if (created == null) {
            lastDetail = "AudioTrack construction failed at both " +
                "$PREFERRED_SAMPLE_RATE_HZ Hz/stereo and $FALLBACK_SAMPLE_RATE_HZ Hz/mono"
            Log.w(TAG, lastDetail)
            return
        }

        track = created
        lastPreferredSet = if (telephonyDevice != null) {
            created.setPreferredDevice(telephonyDevice)
        } else {
            false
        }
        lastDetail = when {
            telephonyDevice == null ->
                "no AudioDeviceInfo of TYPE_TELEPHONY in getDevices(GET_DEVICES_OUTPUTS)"
            !lastPreferredSet -> "setPreferredDevice() returned false for device id ${telephonyDevice.id}"
            else -> "routed to TELEPHONY_TX device id ${telephonyDevice.id}"
        }
        created.play()
    }

    private fun buildTrack(sampleRate: Int, channelMask: Int): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                Log.w(TAG, "getMinBufferSize($sampleRate, $channelMask) returned $minBuf")
                return null
            }
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
            Log.w(TAG, "buildTrack(sampleRate=$sampleRate, channelMask=$channelMask) threw", e)
            null
        }
    }

    private fun findTelephonyTxDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

    override fun write(pcm: ShortArray) {
        val activeTrack = track ?: return
        val samples = if (stereo) upmixToStereo(pcm) else pcm
        activeTrack.write(samples, 0, samples.size)
    }

    override fun flush() {
        val activeTrack = track ?: return
        activeTrack.pause()
        activeTrack.flush()
        activeTrack.play()
    }

    override fun close() {
        track?.let {
            it.stop()
            it.release()
        }
        track = null
    }

    /** `AudioTrack.getRoutedDevice()` — only non-null once actually playing. Exposed for the
     *  on-device test procedure / status screen, per the brief. */
    fun getRoutedDevice(): AudioDeviceInfo? = track?.routedDevice

    override fun probe(): RouteProbe = RouteProbe(
        route = route,
        deviceFound = lastDeviceFound,
        preferredDeviceSet = lastPreferredSet,
        routedDeviceId = track?.routedDevice?.id,
        detail = lastDetail,
    )

    private fun upmixToStereo(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]
            out[i * 2 + 1] = mono[i]
        }
        return out
    }

    companion object {
        private const val TAG = "TelephonyTxInjector"
        private const val PREFERRED_SAMPLE_RATE_HZ = 16_000
        private const val FALLBACK_SAMPLE_RATE_HZ = 8_000
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }
}
