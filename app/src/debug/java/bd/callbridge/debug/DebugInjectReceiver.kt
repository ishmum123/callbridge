package bd.callbridge.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.util.Log
import bd.callbridge.BuildConfig
import bd.callbridge.audio.CaptureWavDumper
import bd.callbridge.audio.Injector
import bd.callbridge.audio.InjectorFactory
import bd.callbridge.audio.InjectorRoute
import bd.callbridge.audio.TelephonyTxInjector
import bd.callbridge.audio.TestTone
import bd.callbridge.audio.VoiceCallCapture
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only on-device test hook (this class only ships in debug builds; registered from
 * `src/debug/AndroidManifest.xml`, never merged into release). Lets an operator drive an
 * injection or capture smoke test mid-call from adb without needing a UI:
 *
 * ```
 * adb shell am broadcast -a bd.callbridge.DEBUG_INJECT --es route TELEPHONY_TX --ei seconds 5
 * adb shell am broadcast -a bd.callbridge.DEBUG_CAPTURE --ei seconds 5
 * ```
 *
 * All work happens off the receiver thread (via [goAsync] + a coroutine on [Dispatchers.IO]) so
 * `onReceive` never blocks; see docs/injection-routes.md for the on-device test procedure this
 * hook exists to support. Everything logs under tag [TAG] ("InjectTest") ending in a single
 * `InjectTest: RESULT ...` / `InjectTest: CAPTURE ...` summary line.
 */
class DebugInjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Belt-and-suspenders: this receiver is only *registered* in debug builds (see
        // src/debug/AndroidManifest.xml, absent from release), but guard the handler body too
        // in case a future refactor ever moves the manifest entry.
        if (!BuildConfig.DEBUG) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        when (intent.action) {
            ACTION_INJECT -> {
                val routeName = intent.getStringExtra(EXTRA_ROUTE) ?: DEFAULT_ROUTE
                val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
                val freqHz = intent.getIntExtra(EXTRA_FREQ, DEFAULT_FREQ_HZ)
                runInjectTest(appContext, routeName, seconds, freqHz, pendingResult)
            }
            ACTION_CAPTURE -> {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
                runCaptureTest(appContext, seconds, pendingResult)
            }
            else -> {
                Log.w(TAG, "unrecognized action: ${intent.action}")
                pendingResult.finish()
            }
        }
    }

    private fun runInjectTest(
        appContext: Context,
        routeName: String,
        seconds: Int,
        freqHz: Int,
        pendingResult: PendingResult,
    ) {
        if (seconds > GOASYNC_WARN_SECONDS) {
            Log.w(TAG, "InjectTest: seconds=$seconds is long for a goAsync() broadcast receiver; the system may reclaim the process before it finishes")
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var injector: Injector? = null
            try {
                val route = try {
                    InjectorRoute.valueOf(routeName)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "InjectTest: RESULT route=$routeName ok=false reason=unknown route name (${InjectorRoute.entries.joinToString()})")
                    return@launch
                }

                Log.i(TAG, "InjectTest: starting route=$route seconds=$seconds freqHz=$freqHz")
                val built = InjectorFactory.create(route, appContext)
                injector = built
                built.open()

                val probe = built.probe()
                Log.i(
                    TAG,
                    "InjectTest: probe route=${probe.route} deviceFound=${probe.deviceFound} " +
                        "preferredDeviceSet=${probe.preferredDeviceSet} routedDeviceId=${probe.routedDeviceId} " +
                        "detail=${probe.detail}",
                )

                if (!probe.deviceFound && route == InjectorRoute.INCALL_MUSIC) {
                    // Route A always reports unavailable by design (see IncallMusicInjector) -
                    // no point streaming a tone into a no-op sink.
                    Log.w(TAG, "InjectTest: RESULT route=$route routedType=none ok=false reason=route reports unavailable (${probe.detail})")
                    return@launch
                }

                logRoutedDevice(built, "MID(pre-write)")

                val chunk = TestTone.generate(durationMs = TONE_CHUNK_MS, frequencyHz = freqHz.toDouble())
                val startMs = System.currentTimeMillis()
                val endAtMs = startMs + seconds * 1000L
                var loggedMid = false
                while (System.currentTimeMillis() < endAtMs) {
                    built.write(chunk)
                    if (!loggedMid && System.currentTimeMillis() - startMs >= MID_POLL_MS) {
                        loggedMid = true
                        logRoutedDevice(built, "MID(~${MID_POLL_MS}ms in)")
                    }
                }

                val finalDevice = logRoutedDevice(built, "END")
                val finalProbe = built.probe()
                val ok = when (route) {
                    InjectorRoute.TELEPHONY_TX -> finalDevice?.type == AudioDeviceInfo.TYPE_TELEPHONY
                    InjectorRoute.LOOPBACK -> finalProbe.routedDeviceId != null
                    InjectorRoute.NOOP -> false
                    InjectorRoute.INCALL_MUSIC -> false
                }
                val routedTypeStr = finalDevice?.let { "${it.type}(${deviceTypeName(it.type)})" }
                    ?: finalProbe.routedDeviceId?.toString() ?: "none"
                Log.i(TAG, "InjectTest: RESULT route=$route routedType=$routedTypeStr ok=$ok")
            } catch (e: Exception) {
                Log.e(TAG, "InjectTest: threw", e)
            } finally {
                try {
                    injector?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "InjectTest: injector.close() threw", e)
                }
                pendingResult.finish()
            }
        }
    }

    /** Logs (and returns) [AudioTrack.getRoutedDevice()][android.media.AudioTrack.getRoutedDevice]
     *  type + productName for routes that expose it. Only [TelephonyTxInjector] exposes a public
     *  getter today (added alongside this test hook); other routes fall back to [Injector.probe]'s
     *  `routedDeviceId`, which is still enough to tell "opened but nothing routed" apart from
     *  "routed to device N". */
    private fun logRoutedDevice(injector: Injector, phase: String): AudioDeviceInfo? {
        val device = (injector as? TelephonyTxInjector)?.getRoutedDevice()
        if (device != null) {
            Log.i(TAG, "InjectTest: $phase routedType=${device.type}(${deviceTypeName(device.type)}) productName=${device.productName}")
        } else {
            Log.i(TAG, "InjectTest: $phase no typed AudioTrack.getRoutedDevice() for this route; probe.routedDeviceId=${injector.probe().routedDeviceId}")
        }
        return device
    }

    private fun runCaptureTest(appContext: Context, seconds: Int, pendingResult: PendingResult) {
        if (seconds > GOASYNC_WARN_SECONDS) {
            Log.w(TAG, "InjectTest: seconds=$seconds is long for a goAsync() broadcast receiver; the system may reclaim the process before it finishes")
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val capturesDir = File(appContext.filesDir, "captures")
            val before = capturesDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

            val dumper = CaptureWavDumper(appContext)
            val capture = VoiceCallCapture(appContext, wavDumper = dumper)

            var frameCount = 0L
            var totalSamples = 0L
            var sumSquares = 0.0
            var peak = 0
            var nonZero = false

            // Per-second bucket, reset every log tick.
            var bucketSamples = 0L
            var bucketSumSquares = 0.0
            var bucketPeak = 0

            val collectJob = launch {
                capture.frames.collect { pcm ->
                    frameCount++
                    for (s in pcm) {
                        val v = s.toInt()
                        if (s != 0.toShort()) nonZero = true
                        val sq = (v * v).toDouble()
                        sumSquares += sq
                        bucketSumSquares += sq
                        totalSamples++
                        bucketSamples++
                        val a = abs(v)
                        if (a > peak) peak = a
                        if (a > bucketPeak) bucketPeak = a
                    }
                }
            }

            try {
                capture.start()
                Log.i(TAG, "InjectTest: CAPTURE started seconds=$seconds state=${capture.state.value}")

                repeat(seconds) {
                    delay(1000)
                    val bucketRms = if (bucketSamples > 0) sqrt(bucketSumSquares / bucketSamples) else 0.0
                    Log.i(TAG, "InjectTest: CAPTURE progress frames=$frameCount peak=$bucketPeak rms=${"%.1f".format(bucketRms)}")
                    bucketSamples = 0
                    bucketSumSquares = 0.0
                    bucketPeak = 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "InjectTest: CAPTURE threw", e)
            } finally {
                collectJob.cancel()
                capture.stop()

                val newFile = capturesDir.listFiles()
                    ?.filterNot { before.contains(it.name) }
                    ?.maxByOrNull { it.lastModified() }
                val overallRms = if (totalSamples > 0) sqrt(sumSquares / totalSamples) else 0.0

                Log.i(
                    TAG,
                    "InjectTest: CAPTURE frames=$frameCount rms=${"%.1f".format(overallRms)} " +
                        "peak=$peak nonzero=$nonZero path=${newFile?.absolutePath ?: "none"}",
                )
                pendingResult.finish()
            }
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BUS -> "TYPE_BUS"
        else -> "UNKNOWN"
    }

    companion object {
        const val TAG = "InjectTest"
        const val ACTION_INJECT = "bd.callbridge.DEBUG_INJECT"
        const val ACTION_CAPTURE = "bd.callbridge.DEBUG_CAPTURE"

        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_FREQ = "freq"

        private const val DEFAULT_ROUTE = "TELEPHONY_TX"
        private const val DEFAULT_SECONDS = 5
        private const val DEFAULT_FREQ_HZ = 1000

        private const val TONE_CHUNK_MS = 200
        private const val MID_POLL_MS = 300L

        /** goAsync() broadcast receivers are expected to finish quickly (system-enforced,
         *  roughly on the order of ~10s); warn rather than silently truncate for longer test
         *  runs so a log-reader knows why a run might not have completed. */
        private const val GOASYNC_WARN_SECONDS = 20
    }
}
