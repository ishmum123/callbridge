# CallBridge — milestone status

Last updated: 2026-09-12 (M0 scaffold commit).

**Device correction:** the pilot phone (SM-G781B) runs **LineageOS 23.2 nightly 20260411, Android 16, SDK 36** — not One UI 13 as `callbridge-spec.md` §2 assumed. `compileSdk`/`targetSdk` are 36 (minSdk stays 33). Root is LineageOS's "Rooted debugging" (`adb root` + `adb remount`), not Magisk — see [`docs/hal-recon.md`](./hal-recon.md) for the full HAL recon (Route A in-call-music is confirmed present on this firmware) and the updated install mechanism, reflected in `scripts/install-privapp.sh`.

## Milestone status

| Milestone | Status |
|---|---|
| M0 — Dialer skeleton | on device: install verified, priv perms granted, default dialer set, first inbound call reached InCallService; callback flow crash fixed (this change). |
| M1a — Capture | Pending. Interfaces (`AudioCapture`) and package (`audio/`) exist; no implementation. |
| M1b — Injection | Pending. `Injector` interface + `NoopInjector` + NDK/CMake toolchain proven (stub `nativeVersion()` JNI call); no real Route A/B/C implementation. |
| M2 — Bridge | Pending. `LiveSession` interface + `UnimplementedLiveSession` stub exist; no OkHttp WebSocket client. |
| M3 — Polish | Pending. |
| M4 — Shop pilot | Pending. |

## What M0 actually built

- Android project skeleton: Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), single `app` module, Gradle wrapper committed (pinned to Gradle 8.14.3 / AGP 8.13.0 / Kotlin 2.0.21).
- NDK/CMake wired: `app/src/main/cpp/CMakeLists.txt` + `native.cpp` build `libcallbridge_native.so` exposing one JNI method, `nativeVersion()`, called from `bd.callbridge.audio.NativeBridge`. `ndkVersion` pinned in `app/build.gradle.kts`; abiFilters `arm64-v8a` only.
- Call control (spec §4.1): `CallStateMachine` (pure Kotlin, fully unit-tested — see `CallStateMachineTest`), `CallController` (drives Telecom `Call` objects from the state machine's actions: answer/reject/disconnect/placeCall/SMS), `CallBridgeInCallService` (registered `InCallService`, default-dialer manifest wiring).
- Storage (spec §4.5): Room database with `callers`/`calls`/`turns` tables exactly as specified, DAOs, `CallerRepository` (interface + Room impl) consulted by the call policy.
- UI (spec §4.6): `StatusActivity` (state/caller/socket/route/calls-today/cost-today labels — currently static placeholders; hang-up button wired to `CallController`, test-injection and register-caller buttons present) and `RegisterCallerActivity` (writes to `callers` table).
- `BridgeForegroundService` + `BootCompletedReceiver`: partial wake lock + Wi-Fi `WIFI_MODE_FULL_HIGH_PERF` lock, started at boot and from `StatusActivity`.
- `Config.kt`: injector route flag (defaults to `InjectorRoute.NOOP`), Gemini model id placeholder (**unverified** — must be checked against current Gemini API docs before M2), API key sourced from `local.properties` via `BuildConfig.GEMINI_API_KEY` (never committed).
- Install path (spec §5, corrected for the real device): `scripts/install-privapp.sh` builds the debug APK, then `adb root && adb remount`s and pushes the APK + `privapp-permissions-callbridge.xml` directly to `/system/priv-app` / `/system/etc/permissions`, then reboots — no Magisk on this phone. The `magisk/` module skeleton (module.prop, privapp-permissions XML exactly per spec §5, sysconfig XML) is kept as an optional path for a Magisk-rooted device. Script is shell-checked (`bash -n`), not verified end-to-end against the phone.
- No `su` binary on this device — anything shelling out to `su -c ...` (e.g. spec §4.3's `tinymix` toggling) is not viable as written; the injector/NDK worker will need a bundled/prebuilt arm64 `tinyalsa`/mixer binary instead, run without `su` since the app itself is already running as a priv-app.

## Handoff: interfaces for the next three workers

All three packages contain doc comments pointing at the exact file/interface to implement; summarized here for quick orientation.

### Audio-pipeline worker (M1a capture + resampling/VAD)
- Implement `bd.callbridge.audio.AudioCapture` (file: `app/src/main/java/bd/callbridge/audio/AudioCapture.kt`) with a concrete `AudioRecord(VOICE_CALL, 8000, ...)` capture class, resampled to 16 kHz (`Config.CAPTURE_SAMPLE_RATE_HZ`), emitting `Flow<ShortArray>`.
- Add VAD gating (WebRTC VAD mode 2, spec §4.2) as a decorator, kept separately testable (round-trip a sine, check THD; VAD gate timing — spec §8).
- Needs `CAPTURE_AUDIO_OUTPUT` — priv-app only, i.e. must run through the Magisk-installed build.

### Injector/NDK worker (M1b injection — the gating problem, spec §4.3)
- `app/src/main/cpp/native.cpp` / `CMakeLists.txt`: currently a stub exposing `nativeVersion()`. Add the real native `AudioTrack` opened with `AUDIO_OUTPUT_FLAG_INCALL_MUSIC` (Route A), linked via `dlsym` against `libaudioclient.so`, with `open/write/flush/close` JNI methods on `bd.callbridge.audio.NativeBridge`.
- Implement `bd.callbridge.audio.Injector` (file: `Injector.kt`) as `InCallMusicInjector` (Route A), `TelephonyTxInjector` (Route B, plain `AudioTrack.setPreferredDevice`), and `LoopbackInjector` (Route C fallback). Wire selection through `bd.callbridge.audio.InjectorRoute` / `Config.injectorRoute`.
- `docs/hal-recon.md` confirms Route A's mixer/audio-policy stanzas (`incall_music_uplink`, `Telephony Tx` device) are present on this firmware, so Route A is the primary path to implement first. There is no `su` binary and no `tinymix` on-device (see above) — any mixer-control toggling needs a bundled/prebuilt arm64 binary invoked directly (the app is already priv-app), not `su -c tinymix ...` as spec §4.3 step 3 literally says. Record the working control set in this doc once found.

### Gemini-session worker (M2 bridge)
- Implement `bd.callbridge.gemini.LiveSession` (file: `LiveSession.kt`) as a real OkHttp WebSocket client, replacing `UnimplementedLiveSession`.
- **Verify the current Gemini Flash Live model id** against the Gemini API docs before replacing `Config.GEMINI_MODEL_ID` (currently a placeholder).
- Setup message per spec §4.4: audio response modality, Bangla voice, system prompt (spec §6) with `CallerProfile` interpolated, input/output transcription enabled.
- Implement the 8 s watchdog (spec §4.4) at the layer that owns the session (not inside `LiveSession` itself), so it stays unit-testable independent of the socket.
- `sendAudio` takes 16 kHz PCM16 mono ~100 ms chunks; `AudioOut` events are 24 kHz PCM16, need resampling to 8 kHz before handing to `Injector.write()`.

## Known deviations / open items from the spec

- `CAPTURE_AUDIO_OUTPUT` / `MODIFY_PHONE_STATE` / etc. are declared in the manifest but are only functional once installed as a priv-app via the Magisk module — expected per spec §5, not a bug.
- Gemini model id and Live API pricing figures in `callbridge-spec.md` §2/§9 are explicitly marked "verify" in the spec; `Config.GEMINI_MODEL_ID` is a placeholder pending that verification.
- No on-device testing was possible during the M0 build (no phone attached). All acceptance was via `./gradlew assembleDebug`/`test`/`lint` and `bash -n` on the install script.
