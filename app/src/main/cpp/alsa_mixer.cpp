// Minimal ALSA control-interface helper for CallBridge M1b (spec §4.3 step 3).
//
// The pilot phone (docs/hal-recon.md) has no `tinymix` binary and no `su`, so the mixer
// controls that gate the Route A in-call-music path (`Incall_Music Audio Mixer MultiMedia9`,
// `Incall_Music_2 Audio Mixer MultiMedia9`, from mixer_paths.xml) have to be toggled directly
// from the app via the kernel ALSA control-interface ioctls on /dev/snd/controlC<n>.
//
// Rather than vendoring tinyalsa's mixer.c (which itself is a thin wrapper over exactly these
// ioctls) or a network FetchContent at build time, this talks to the kernel UAPI directly using
// <sound/asound.h>, which the NDK r27 sysroot ships verbatim from the upstream kernel headers
// (toolchains/llvm/prebuilt/*/sysroot/usr/include/sound/asound.h) — so the struct layouts and
// ioctl numbers are exactly what the kernel expects, no hand-transcription risk.
//
// SNDRV_CTL_IOCTL_ELEM_READ / _WRITE accept a snd_ctl_elem_value with id.numid == 0 and
// id.iface/id.name/id.index filled in; the kernel's snd_ctl_find_id() resolves by name in that
// case (see sound/core/control.c), so we don't need ELEM_LIST/ELEM_INFO first for a
// known-by-name integer/boolean control.
#include <jni.h>
#include <string>
#include <cstring>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <sound/asound.h>

#define LOG_TAG "CallBridgeMixer"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Opens /dev/snd/controlC<card> read-write. Returns -1 (with errno set) on failure — most
// likely EACCES, since a priv-app is not guaranteed to be in the `audio` group (spec/brief
// explicitly calls this out as an expected failure mode, not a bug).
int openControlDevice(int card) {
    std::string path = "/dev/snd/controlC" + std::to_string(card);
    int fd = ::open(path.c_str(), O_RDWR);
    if (fd < 0) {
        LOGW("open(%s) failed: %s", path.c_str(), strerror(errno));
    }
    return fd;
}

void fillId(snd_ctl_elem_id *id, const char *name) {
    memset(id, 0, sizeof(*id));
    id->iface = SNDRV_CTL_ELEM_IFACE_MIXER;
    // name is a fixed 44-byte buffer, not guaranteed NUL-terminated by the kernel side, but
    // strncpy + leaving the rest zeroed (from memset above) matches what tinyalsa/alsa-lib do.
    strncpy(reinterpret_cast<char *>(id->name), name, sizeof(id->name) - 1);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_bd_callbridge_audio_MixerControl_nativeGetControl(JNIEnv *env, jobject /* this */,
                                                          jint card, jstring nameJ) {
    const char *name = env->GetStringUTFChars(nameJ, nullptr);
    int fd = openControlDevice(card);
    if (fd < 0) {
        env->ReleaseStringUTFChars(nameJ, name);
        return -errno;
    }

    snd_ctl_elem_value value{};
    fillId(&value.id, name);
    env->ReleaseStringUTFChars(nameJ, name);

    int rc = ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &value);
    int savedErrno = errno;
    close(fd);
    if (rc < 0) {
        LOGW("ELEM_READ failed: %s", strerror(savedErrno));
        return -savedErrno;
    }
    // All controls we care about (Incall_Music* Audio Mixer MultiMedia9) are boolean/integer
    // switches per mixer_paths.xml (value 0/1); integer.value[0] covers both.
    return static_cast<jint>(value.value.integer.value[0]);
}

extern "C" JNIEXPORT jint JNICALL
Java_bd_callbridge_audio_MixerControl_nativeSetControl(JNIEnv *env, jobject /* this */,
                                                          jint card, jstring nameJ, jint newValue) {
    const char *name = env->GetStringUTFChars(nameJ, nullptr);
    std::string nameCopy(name); // keep for logging after we release the JNI string
    int fd = openControlDevice(card);
    if (fd < 0) {
        env->ReleaseStringUTFChars(nameJ, name);
        return -errno;
    }

    snd_ctl_elem_value value{};
    fillId(&value.id, name);
    env->ReleaseStringUTFChars(nameJ, name);
    value.value.integer.value[0] = newValue;

    int rc = ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &value);
    int savedErrno = errno;
    close(fd);
    if (rc < 0) {
        LOGW("ELEM_WRITE failed: %s", strerror(savedErrno));
        return -savedErrno;
    }
    LOGI("set '%s' = %d on card %d", nameCopy.c_str(), newValue, card);
    return 0;
}
