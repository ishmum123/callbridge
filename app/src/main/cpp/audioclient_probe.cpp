// Route A (spec §4.3 step 2) research finding, recorded here rather than only in
// docs/injection-routes.md because the conclusion IS the implementation: this file
// deliberately stops at "probe", it does not construct a native android::AudioTrack.
//
// Why option (a) [dlopen libaudioclient.so, hand-build android::AudioTrack with
// AUDIO_OUTPUT_FLAG_INCALL_MUSIC] is not viable in an app process on Android 16 (AOSP
// android-16 frameworks/av):
//
//  1. libaudioclient.so is not in the app-visible linker namespace. From code loaded via
//     System.loadLibrary() (the "classloader-namespace"), bionic's namespace config
//     (ld.config.*.txt) does not expose it — it isn't on the app-facing public.libraries.txt
//     allowlist and hasn't been for years, specifically to keep this ABI free to churn.
//     A plain dlopen("libaudioclient.so") fails; nativeProbeAudioClient() below tries anyway,
//     then tries the documented namespace-bypass technique (android_create_namespace +
//     android_dlopen_ext with ANDROID_NAMESPACE_TYPE_SHARED|ISOLATED, permitted path
//     /system/lib64:/system/lib64/vndk, linked to the caller's default namespace — see
//     https://fadeevab.com/accessing-system-private-api-through-namespace/, originally
//     documented against Android 8) purely to report whether the library can be *seen* at all
//     on this build. That result is diagnostic only.
//
//  2. Even if the dlopen succeeds, android::AudioTrack's current public constructor
//     (frameworks/av/media/libaudioclient/include/media/AudioTrack.h, android-16 `main`)
//     takes an `AttributionSourceState` — a Parcelable generated from an AIDL union — by
//     value, plus depends on libbinder's `Parcel`/`sp<>`/`wp<>` (libutils RefBase) ABI, and
//     `audio_attributes_t`/`audio_offload_info_t` layouts from libaudiofoundation. None of
//     these are part of the NDK's stable ABI, none are exported to apps, and their layouts
//     are free to change release to release (that's the whole reason they're kept off the
//     app-facing namespace). Hand-mangling the constructor's Itanium symbol and poking a
//     same-shaped-looking AttributionSourceState onto the stack would be guessing at an ABI
//     Google explicitly does not promise to hold still — a mismatch doesn't fail cleanly, it
//     corrupts the stack/heap of a process that also owns the live call's audio path. That
//     risk is not worth taking on a pilot device we can't yet fully bisect on-device.
//
// Conclusion: implement Route A as a safe, non-destructive probe (this file), let
// IncallMusicInjector.open() report `deviceFound = false` with the probe detail when
// libaudioclient.so isn't reachable (the expected case), and rely on the spec's own ordering
// (§4.3/§7 M1b: "Route B -> Route A -> Route C") — Route B (TelephonyTxInjector) is the real
// primary path since the TELEPHONY_TX device port is reachable through public
// AudioTrack.setPreferredDevice(). If a future milestone gets a live device + the ability to
// pull this exact build's libaudioclient.so for offline symbol/ABI analysis, this file is
// where a real construction attempt would go, gated behind the probe succeeding.
#include <jni.h>
#include <string>
#include <dlfcn.h>
#include <android/dlext.h>
#include <android/log.h>

#define LOG_TAG "CallBridgeRouteA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kLib = "libaudioclient.so";
constexpr const char *kPermittedPaths = "/system/lib64:/system/lib64/vndk";

// android_create_namespace() has no NDK declaration — <android/dlext.h> says so explicitly
// ("This flag is for internal use only (since there is no NDK API for namespaces)"). It is a
// real exported symbol on the linker's libdl.so trampoline though (every namespace gets one),
// which is the basis of the documented bypass technique (see file header comment / the
// fadeevab.com "Bypassing the Android Linker Namespace" writeup, originally against Android 8;
// the mechanism is unchanged through source.android.com's current linker-namespace docs).
// Resolve it by hand rather than declaring it directly, since there is no header to include.
using android_namespace_t = struct android_namespace_t;
using CreateNamespaceFn = android_namespace_t *(*)(const char *name,
                                                     const char *ld_library_path,
                                                     const char *default_library_path,
                                                     uint64_t type,
                                                     const char *permitted_when_isolated_path,
                                                     android_namespace_t *parent_namespace);

// Bionic linker_namespaces.h values (private header, not shipped by the NDK). Stable since
// namespace isolation was introduced in Android 7/8 — reused verbatim by every published
// bypass write-up, e.g. the fadeevab.com post cited above.
constexpr uint64_t kNamespaceTypeIsolated = 1;
constexpr uint64_t kNamespaceTypeShared = 2;

// Best-effort namespace-bypass load, purely to answer "can this process see the library at
// all", not to use it. Returns the dlopen handle (caller must dlclose) or nullptr.
void *tryNamespaceBypassOpen(std::string *detailOut) {
    auto createNamespace = reinterpret_cast<CreateNamespaceFn>(
        dlsym(RTLD_DEFAULT, "android_create_namespace"));
    if (createNamespace == nullptr) {
        *detailOut += "; dlsym(android_create_namespace) not found";
        return nullptr;
    }

    android_namespace_t *ns = createNamespace(
        "callbridge_probe",
        nullptr,               // ld_library_path
        nullptr,                // default_library_path
        kNamespaceTypeIsolated | kNamespaceTypeShared,
        kPermittedPaths,        // permitted_when_isolated_path
        nullptr);               // parent: none -> not linked to caller's namespace at all
    if (ns == nullptr) {
        *detailOut += "; android_create_namespace failed: " + std::string(dlerror() ? dlerror() : "unknown");
        return nullptr;
    }

    android_dlextinfo extinfo{};
    extinfo.flags = ANDROID_DLEXT_USE_NAMESPACE;
    extinfo.library_namespace = ns;
    void *handle = android_dlopen_ext(kLib, RTLD_NOW, &extinfo);
    if (handle == nullptr) {
        *detailOut += "; android_dlopen_ext(namespace-bypass) failed: " + std::string(dlerror() ? dlerror() : "unknown");
    } else {
        *detailOut += "; android_dlopen_ext(namespace-bypass) SUCCEEDED";
    }
    return handle;
}

} // namespace

// Returns a human-readable diagnostic string; never throws, never crashes. Used only to
// decide (and to document in docs/injection-routes.md) whether Route A has any future short
// of a full ABI-matched native AudioTrack build, which this shim deliberately does not
// attempt (see file header). true "available" would still just mean "the .so is loadable",
// not "Route A works" — IncallMusicInjector treats Route A as unavailable regardless.
extern "C" JNIEXPORT jstring JNICALL
Java_bd_callbridge_audio_NativeBridge_nativeProbeAudioClient(JNIEnv *env, jobject /* this */) {
    std::string detail = "plain dlopen(" + std::string(kLib) + ")";
    void *handle = dlopen(kLib, RTLD_NOW);
    if (handle != nullptr) {
        detail += " SUCCEEDED (unexpected on a hardened build)";
        dlclose(handle);
    } else {
        detail += " failed: " + std::string(dlerror() ? dlerror() : "unknown");
        handle = tryNamespaceBypassOpen(&detail);
        if (handle != nullptr) {
            dlclose(handle);
        }
    }
    LOGI("%s", detail.c_str());
    return env->NewStringUTF(detail.c_str());
}
