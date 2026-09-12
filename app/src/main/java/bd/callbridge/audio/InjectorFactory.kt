package bd.callbridge.audio

import android.content.Context

/** Builds the [Injector] for [bd.callbridge.Config.injectorRoute] (spec §4.3 deliverable:
 *  "`Injector` interface with A/B/C implementations, selected by a config flag"). */
object InjectorFactory {
    fun create(route: InjectorRoute, context: Context): Injector = when (route) {
        InjectorRoute.INCALL_MUSIC -> IncallMusicInjector()
        InjectorRoute.TELEPHONY_TX -> TelephonyTxInjector(context)
        InjectorRoute.LOOPBACK -> LoopbackInjector(context)
        InjectorRoute.NOOP -> NoopInjector()
    }
}
