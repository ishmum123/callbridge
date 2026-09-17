package bd.callbridge.audio

import android.media.AudioDeviceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression test for the on-device wait-clip SIGSEGV (tombstones 2026-09-12/2026-09-17):
 * `TelephonyTxInjector.write()` racing `close()`'s `AudioTrack.stop()+release()` from another
 * thread crashed the whole process with a native null-pointer deref in
 * `AudioTrack::releaseBuffer`. A real `AudioTrack`'s behavior past `release()` is native and
 * can't be safely exercised or asserted on off-device, so this test substitutes a fake
 * [TrackHandle] (via [TelephonyTxInjector]'s `trackFactory` seam) that records whether any of
 * its methods is ever invoked after its own `release()` — which is exactly the shape of the
 * crash, one level up from the native call. Hammers [TelephonyTxInjector.write]/`flush` from
 * several threads while [TelephonyTxInjector.close] runs concurrently, many times, and asserts
 * the count of post-release calls is always zero (i.e. the injector's lock/`closed` guard holds
 * under contention).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelephonyTxInjectorConcurrencyTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Records any call made after [release] — the JVM-observable analogue of the native
     *  use-after-free this test guards against. Never throws (so one violation doesn't stop the
     *  rest of the run from being counted); [postReleaseCallCount] is asserted at the end. */
    private class FakeTrackHandle : TrackHandle {
        private val released = AtomicBoolean(false)
        val postReleaseCallCount = AtomicInteger(0)
        val totalWriteCount = AtomicInteger(0)

        private fun noteCall() {
            if (released.get()) postReleaseCallCount.incrementAndGet()
        }

        override fun write(samples: ShortArray, offset: Int, length: Int) {
            noteCall()
            totalWriteCount.incrementAndGet()
        }

        override fun play() = noteCall()
        override fun pause() = noteCall()
        override fun flush() = noteCall()
        override fun stop() = noteCall()
        override fun release() {
            noteCall()
            released.set(true)
        }

        override fun setPreferredDevice(device: AudioDeviceInfo): Boolean {
            noteCall()
            return true
        }

        override val routedDevice: AudioDeviceInfo? get() = null
    }

    @Test
    fun `write and flush never touch the track after close releases it, under concurrent hammering`() {
        repeat(ITERATIONS) { iteration ->
            val fake = FakeTrackHandle()
            val injector = TelephonyTxInjector(context, trackFactory = { _, _ -> fake })
            injector.open()

            val startLatch = CountDownLatch(1)
            val pcm = ShortArray(160) // 20 ms @ 8 kHz mono, arbitrary content
            val writerThreads = (1..WRITER_THREADS).map { idx ->
                Thread({
                    startLatch.await()
                    var n = 0
                    while (n < WRITES_PER_THREAD) {
                        if (n % 5 == 0) injector.flush() else injector.write(pcm)
                        n++
                    }
                }, "writer-$idx-iter$iteration")
            }
            val closerThread = Thread({
                startLatch.await()
                // No sleep: we want close() racing the very first writes, not politely waiting
                // its turn — that's the failure mode observed on-device (a barge-in/hangup
                // stop() landing mid wait-clip write).
                injector.close()
            }, "closer-iter$iteration")

            (writerThreads + closerThread).forEach { it.start() }
            startLatch.countDown()
            (writerThreads + closerThread).forEach { thread ->
                thread.join(JOIN_TIMEOUT_MS)
                assertTrue("thread ${thread.name} did not finish in time", !thread.isAlive)
            }

            assertEquals(
                "a TrackHandle method was called after release() on iteration $iteration " +
                    "(totalWrites=${fake.totalWriteCount.get()}) — the write-vs-close race is back",
                0,
                fake.postReleaseCallCount.get(),
            )

            // Idempotent / safe post-close calls, exercised from the main thread too.
            injector.write(pcm)
            injector.flush()
            injector.close()
            assertEquals(0, fake.postReleaseCallCount.get())
        }
    }

    companion object {
        private const val ITERATIONS = 20
        private const val WRITER_THREADS = 6
        private const val WRITES_PER_THREAD = 50
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
