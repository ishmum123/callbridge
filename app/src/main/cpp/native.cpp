// M0 stub, kept minimal so the scaffold links and the Gradle/CMake toolchain is proven end to
// end. M1b's real native work (Route A research probe, ALSA mixer control helper) lives in
// audioclient_probe.cpp and alsa_mixer.cpp respectively — see those files for the full
// reasoning on why Route A stops at a probe rather than a full native AudioTrack shim.
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_bd_callbridge_audio_NativeBridge_nativeVersion(JNIEnv *env, jobject /* this */) {
    std::string version = "callbridge_native-0.1.0-m0-stub";
    return env->NewStringUTF(version.c_str());
}
