// M0 stub. The real injection shim (Route A: AUDIO_OUTPUT_FLAG_INCALL_MUSIC via
// libaudioclient.so dlsym, per spec §4.3) lands here in M1b. Kept minimal so the
// scaffold links and the Gradle/CMake toolchain is proven end to end.
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_bd_callbridge_audio_NativeBridge_nativeVersion(JNIEnv *env, jobject /* this */) {
    std::string version = "callbridge_native-0.1.0-m0-stub";
    return env->NewStringUTF(version.c_str());
}
