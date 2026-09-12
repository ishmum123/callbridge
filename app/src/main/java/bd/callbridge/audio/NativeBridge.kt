package bd.callbridge.audio

/**
 * JNI bridge into libcallbridge_native.so. M0 exposes only [nativeVersion] to prove the
 * NDK/CMake toolchain end to end. The M1b injection worker adds the real Route A
 * (AUDIO_OUTPUT_FLAG_INCALL_MUSIC) native methods here (open/write/flush/close), backing
 * an [Injector] implementation registered in [InjectorRoute.TELEPHONY_TX] /
 * [InjectorRoute.INCALL_MUSIC].
 */
object NativeBridge {
    init {
        System.loadLibrary("callbridge_native")
    }

    @JvmStatic
    external fun nativeVersion(): String
}
