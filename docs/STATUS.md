# CallBridge — milestone status

Last updated: 2026-09-12 (M0 scaffold commit).

**Device correction:** the pilot phone (SM-G781B) runs **LineageOS 23.2 nightly 20260411, Android 16, SDK 36** — not One UI 13 as `callbridge-spec.md` §2 assumed. `compileSdk`/`targetSdk` are 36 (minSdk stays 33). Root is LineageOS's "Rooted debugging" (`adb root` + `adb remount`), not Magisk — see [`docs/hal-recon.md`](./hal-recon.md) for the full HAL recon (Route A in-call-music is confirmed present on this firmware) and the updated install mechanism, reflected in `scripts/install-privapp.sh`.

## Milestone status

| Milestone | Status |
|---|---|
| M0 — Dialer skeleton | Code-complete, unit-tested. **Untested on a real device** (no phone attached during this build). |
| M1a — Capture | Code-complete, unit-tested (resampler THD/SNR, chunk-boundary continuity, VAD timing, mono→stereo). **Untested on a real device** — `AudioSource.VOICE_DOWNLINK`/`VOICE_CALL` need the priv-app install to actually initialize (`CAPTURE_AUDIO_OUTPUT`). |
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

### Audio-pipeline worker (M1a capture + resampling/VAD) — DONE (this build, code review pending)
- `VoiceCallCapture` (`audio/VoiceCallCapture.kt`) implements `AudioCapture`: `AudioRecord` at 8 kHz mono PCM16, tries `AudioSource.VOICE_DOWNLINK` first (per `docs/hal-recon.md`'s confirmed downlink-only usecase) then falls back to `VOICE_CALL`; read loop on a dedicated `Thread`; resamples to `Config.CAPTURE_SAMPLE_RATE_HZ` (16 kHz) before emitting on `frames`. Handles `AudioRecord` init failure (both sources) by logging and leaving `isCapturing == false`, never crashes. `start()`/`stop()` are idempotent.
- `CaptureWavDumper` (`audio/CaptureWavDumper.kt`): optional raw 8 kHz PCM WAV dump to `filesDir/captures/<timestamp>.wav` — the M1a "listen to it" deliverable. Pass an instance into `VoiceCallCapture`'s constructor to enable; not wired in by default.
- `Resampler` (`audio/Resampler.kt`): pure-Kotlin, stateful, windowed-sinc (Hann) fixed-ratio resampler, `Resampler(inRate, outRate).process(ShortArray): ShortArray`. Handles 8→16 kHz (capture) and 24→8/16 kHz (Gemini output → injector). `Resampler.monoToStereo()` companion helper for injector paths needing stereo (hal-recon.md). Has an inherent ~`halfWidth` (default 32) input-sample lookahead latency — negligible at 100 ms chunk sizes, but means the very last ~32/inRate seconds of a stream won't flush without extra trailing samples; harmless for a continuous call stream.
- `VadGate` (`audio/VadGate.kt`): pure-Kotlin energy/RMS gate (not a WebRTC VAD port — simpler and sufficient; swappable behind the same API later if needed), 20 ms frames (320 samples @ 16 kHz), 200 ms pre-roll ring buffer, 400 ms hold after speech ends, `SpeechStarted`/`SpeechEnded` events for barge-in.
- `AudioPipeline` (`audio/AudioPipeline.kt`): composes `capture.frames` → 20 ms re-framing → `VadGate` → 100 ms (1600-sample) `Flow<ShortArray>` chunks (spec §4.4 send size) on `.chunks`, plus `.vadEvents: SharedFlow<VadGate.Event>`. This is what phase 3 hands to `LiveSession`.
- Unit tests: `app/src/test/java/bd/callbridge/audio/ResamplerTest.kt` (THD/SNR bound via best-lag cross-correlation, length ratio, chunk-boundary continuity via irregular-chunk streaming vs. one big call, mono→stereo, a 16→24→16 kHz round trip) and `VadGateTest.kt` (silence→tone→silence timing, pure-silence no-op, frame-size validation). All pass; `./gradlew assembleDebug`/`test`/`lint` green.
- Still needs `CAPTURE_AUDIO_OUTPUT` — priv-app only, i.e. must run through the priv-app install (`scripts/install-privapp.sh`) to actually initialize on-device; untested on the real phone (no device access from this worker).

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
