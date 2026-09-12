package bd.callbridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Robolectric gives us a real (shadow) [android.content.Context] so we can construct every
 *  route's [Injector] and check it reports the right [InjectorRoute] — without touching any
 *  actual audio hardware or the native library. Pinned to `sdk = 34`: the project's
 *  `compileSdk`/`targetSdk` (36, spec/hal-recon corrected value) is newer than what the
 *  installed Robolectric 4.13 ships shadows for; this only affects which framework jar the
 *  test runs against, not app code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InjectorFactoryTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `NOOP route builds a NoopInjector`() {
        val injector = InjectorFactory.create(InjectorRoute.NOOP, context)
        assertTrue(injector is NoopInjector)
        assertEquals(InjectorRoute.NOOP, injector.route)
    }

    @Test
    fun `INCALL_MUSIC route builds an IncallMusicInjector`() {
        val injector = InjectorFactory.create(InjectorRoute.INCALL_MUSIC, context)
        assertTrue(injector is IncallMusicInjector)
        assertEquals(InjectorRoute.INCALL_MUSIC, injector.route)
    }

    @Test
    fun `TELEPHONY_TX route builds a TelephonyTxInjector`() {
        val injector = InjectorFactory.create(InjectorRoute.TELEPHONY_TX, context)
        assertTrue(injector is TelephonyTxInjector)
        assertEquals(InjectorRoute.TELEPHONY_TX, injector.route)
    }

    @Test
    fun `LOOPBACK route builds a LoopbackInjector`() {
        val injector = InjectorFactory.create(InjectorRoute.LOOPBACK, context)
        assertTrue(injector is LoopbackInjector)
        assertEquals(InjectorRoute.LOOPBACK, injector.route)
    }

    @Test
    fun `every InjectorRoute value is handled`() {
        // Exhaustiveness guard: if InjectorRoute grows a case without a matching branch in
        // InjectorFactory, the `when` there stops compiling — this test just documents that
        // every enum entry has been exercised above.
        val handled = setOf(
            InjectorRoute.NOOP,
            InjectorRoute.INCALL_MUSIC,
            InjectorRoute.TELEPHONY_TX,
            InjectorRoute.LOOPBACK,
        )
        assertEquals(InjectorRoute.entries.toSet(), handled)
    }
}
