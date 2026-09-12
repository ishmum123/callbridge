# CallBridge — milestone status

Last updated: 2026-09-12 (M0 scaffold commit).

**Device correction:** the pilot phone (SM-G781B) runs **LineageOS 23.2 nightly 20260411, Android 16, SDK 36** — not One UI 13 as `callbridge-spec.md` §2 assumed. `compileSdk`/`targetSdk` are 36 (minSdk stays 33). Root is LineageOS's "Rooted debugging" (`adb root` + `adb remount`), not Magisk — see [`docs/hal-recon.md`](./hal-recon.md) for the full HAL recon (Route A in-call-music is confirmed present on this firmware) and the updated install mechanism, reflected in `scripts/install-privapp.sh`.

## Milestone status

| Milestone | Status |
|---|---|
| M0 — Dialer skeleton | on device: install verified, priv perms granted, default dialer set, first inbound call reached InCallService; callback flow crash fixed; demo flag `Config.ANSWER_UNREGISTERED_CALLERS=true` answers every caller (verified: inbound call auto-answered and held ACTIVE 2026-09-12 12:48). |
| M1a — Capture | Code-complete, unit-tested (resampler THD/SNR, chunk-boundary continuity, VAD timing, mono→stereo). **Untested on a real device** — `AudioSource.VOICE_DOWNLINK`/`VOICE_CALL` need the priv-app install to actually initialize (`CAPTURE_AUDIO_OUTPUT`). |
| M1b — Injection | Code-complete, unit-tested, **untested on a real device**. `TelephonyTxInjector` (Route B, the real primary path), `LoopbackInjector` (Route C), `InjectorFactory`, `TestTone`, and a `MixerControl` ALSA-ioctl helper are implemented. `IncallMusicInjector` (Route A) is a researched dead end — see `docs/injection-routes.md` — the HAL/mixer stanzas exist but no app-level mechanism (native ABI, hidden Java API) can reach `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`; it always reports unavailable and falls through to B/C, matching spec's own "B → A → C" order. |
| M2 — Bridge | Code-complete, unit-tested + live-smoke-tested against the real Gemini Live API. `GeminiLiveSession` (real OkHttp WebSocket client), `TranscriptRecorder`, `CostModel`, `SystemPromptBuilder` land in `gemini/`. Not yet wired to `audio/`/`Injector`/`service/` (that's M3). See [`docs/gemini-live.md`](./gemini-live.md). |
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

### Audio-pipeline worker (M1a capture + resampling/VAD) — DONE (this build, review fixes applied)
- `VoiceCallCapture` (`audio/VoiceCallCapture.kt`) implements `AudioCapture`: `AudioRecord` at 8 kHz mono PCM16, tries `AudioSource.VOICE_DOWNLINK` first (per `docs/hal-recon.md`'s confirmed downlink-only usecase) then falls back to `VOICE_CALL`; read loop on a dedicated `Thread`, reading fixed 20 ms pieces (not the whole 4×-min ring at once) to bound latency/overrun risk; resamples to `Config.CAPTURE_SAMPLE_RATE_HZ` (16 kHz) before emitting on `frames`. `start()`/`stop()` are `@Synchronized` and idempotent. **Teardown ordering fix:** `stop()` now calls `AudioRecord.stop()` *before* joining the read thread (unblocks a `read()` parked in the HAL, which is the normal case at call teardown), joins with a 5 s timeout, and only releases/nulls the `AudioRecord` once the thread has actually exited — avoids a native use-after-free that the old join(500ms)-then-release ordering could hit. The read thread's `finally` always clears `running`, so a dead/errored thread can no longer wedge `isCapturing` permanently true.
- **Interface change:** `AudioCapture` gained `val state: StateFlow<CaptureState>` (`Idle`/`Running`/`Failed(reason)`) alongside `isCapturing`, so a status screen can see *why* capture stopped (init failure vs. read-thread death vs. a normal `stop()`), not just that it did. `VoiceCallCapture` publishes into it at every failure point that previously only logged.
- `VoiceCallCapture` also logs a rate-limited (~5 s) dropped-frame count when `frames` has no/slow collector (emission is drop-not-block via `tryEmit`, documented on the interface).
- `CaptureWavDumper` (`audio/CaptureWavDumper.kt`): optional raw 8 kHz PCM WAV dump to `filesDir/captures/<timestamp>.wav` — the M1a "listen to it" deliverable. Pass an instance into `VoiceCallCapture`'s constructor to enable; not wired in by default. `start`/`write`/`stop` are now `@Synchronized` (write happens on the capture read thread, start/stop from a lifecycle thread), and writes stop (with one rate-limited log line) once the file reaches a 50 MB cap (`maxFileSizeBytes` constructor param).
- `Resampler` (`audio/Resampler.kt`): pure-Kotlin, stateful, windowed-sinc (Hann) fixed-ratio resampler, `Resampler(inRate, outRate).process(ShortArray): ShortArray`. Handles 8→16 kHz (capture) and 24→8/16 kHz (Gemini output → injector). `Resampler.monoToStereo()` companion helper for injector paths needing stereo (hal-recon.md). Now precomputes a polyphase coefficient table per (inRate, outRate) phase instead of evaluating `sin`/`cos` per output sample per tap (falls back to per-sample evaluation only for pathological rate pairs with a huge phase count); the low-pass cutoff backoff (10%) is now applied only when downsampling, matching the doc comment (upsampling uses the full input Nyquist, as before but now actually reflected in code). Output buffer is a manually-grown `ShortArray`, not boxed `ArrayList<Short>`.
- `VadGate` (`audio/VadGate.kt`): pure-Kotlin energy/RMS gate (not a WebRTC VAD port — simpler and sufficient; swappable behind the same API later if needed), 20 ms frames (320 samples @ 16 kHz), 200 ms pre-roll ring buffer, 400 ms hold after speech ends (hold timer resets the moment voice reappears mid-hold), `SpeechStarted`/`SpeechEnded` events for barge-in. **Now adaptive:** the working voice threshold is `noiseFloor + energyMargin` (`energyMargin`/`minThreshold` constructor params, defaults as `VadGate.DEFAULT_*` constants in this file, not `Config.kt`), where `noiseFloor` is a decaying min-tracker (drops instantly to a quieter frame, rises slowly otherwise) so a long utterance can't drag the floor up mid-speech. **Onset hysteresis:** `SpeechStarted` now requires `onsetFrames` (default 3) consecutive voiced frames rather than firing on the first one, cutting single-frame noise blips. Rate-limited (~5 s) RMS/floor/threshold debug logging for on-device tuning.
- `AudioPipeline` (`audio/AudioPipeline.kt`): composes `capture.frames` → 20 ms re-framing → `VadGate` → 100 ms (1600-sample) `Flow<ShortArray>` chunks (spec §4.4 send size) on `.chunks`, plus `.vadEvents: SharedFlow<VadGate.Event>`. **Semantics now:** collecting `.chunks` actually drives `capture.start()`/`capture.stop()` via `onStart`/`onCompletion` on the flow (previously only documented, not implemented) — `.start()`/`.stop()` remain as idempotent explicit alternatives. A fresh `VadGate` is built per *collection* via a `vadGateFactory: () -> VadGate` constructor param (replacing the old shared single-`VadGate`-instance param) so gate state (noise floor, hold timer, pre-roll) never leaks between calls or across concurrent collectors. `vadEvents` is now `replay = 1` + `DROP_OLDEST` (was replay 0 + suspend-on-full, which could drop events with no subscriber or stall the audio path with a slow one) — events may coalesce under a slow subscriber, by design. **Tail flush:** the last (possibly short) chunk of each utterance is now zero-padded and emitted the moment `SpeechEnded` fires, instead of being withheld and prepended to the next utterance's audio.
- Unit tests: `ResamplerTest.kt` (SNR now measured via phase-independent DFT — fundamental bin vs. everything else over an exact-integer-cycle window — instead of best-lag cross-correlation, which floored reported SNR ~30 dB regardless of true quality; asserts >60 dB on all three primary resample paths), `VadGateTest.kt` (onset hysteresis timing, empty-pre-roll first-frame onset, hold-timer reset mid-hold, two distinct consecutive utterances, default-threshold sanity), `AudioPipelineTest.kt` (new — fixed chunk size, gated-sample-count vs. expectation, `SpeechEnded` tail-flush non-bleed between utterances, VAD event replay for a late subscriber), `CaptureWavDumperTest.kt` (new, Robolectric-backed — RIFF header field-by-field + data length after `stop()`, size-cap enforcement). All pass: 24 tests across 5 suites (`AudioPipelineTest` 3, `CaptureWavDumperTest` 2, `ResamplerTest` 6, `VadGateTest` 7, `CallStateMachineTest` 6, unaffected); `./gradlew test`/`assembleDebug`/`lint` all green.
- Still needs `CAPTURE_AUDIO_OUTPUT` — priv-app only, i.e. must run through the priv-app install (`scripts/install-privapp.sh`) to actually initialize on-device; untested on the real phone (no device access from this worker).

### Injector/NDK worker (M1b injection — the gating problem, spec §4.3) — DONE, pending device verification
- Done: `Injector`/`InjectorRoute`/`RouteProbe`/`TestTone`/`InjectorFactory` and the three route classes (`TelephonyTxInjector`, `IncallMusicInjector`, `LoopbackInjector`) in `app/src/main/java/bd/callbridge/audio/`; `MixerControl` (+ `AlsaControlNames`) for ALSA control toggling; native additions `app/src/main/cpp/audioclient_probe.cpp` (Route A research probe) and `alsa_mixer.cpp` (mixer ioctl helper). Full writeup and per-route test procedure: **`docs/injection-routes.md`**.
- Route A finding: not implementable from an app process on this OS build (native ABI too unstable to hand-construct, no hidden Java API exposes the needed output flag) — see `docs/injection-routes.md` for the three options considered and why each was rejected. `IncallMusicInjector` always reports unavailable; this doesn't block the milestone since spec's own order tries Route B first.
- Route B (`TelephonyTxInjector`) is the real primary path — `AudioTrack.setPreferredDevice(TELEPHONY_TX)`, 16 kHz/stereo preferred (falls back to 8 kHz/mono), reports a `RouteProbe` (device found / preferred-device-set / routed device id) for on-device diagnosis.
- Mixer toggling (spec §4.3 step 3) has no `su`/`tinymix` on this device (see above), so `MixerControl` talks to `/dev/snd/controlC0` directly via kernel ALSA control ioctls instead of shelling out; gracefully reports `PermissionDenied` if the priv-app isn't in the `audio` group. **Not yet run on-device** — working control values still need recording in `docs/injection-routes.md`'s log table once a phone is available.
- Everything above is unit-tested (`TestToneTest`, `InjectorFactoryTest`, `MixerControlTest`) and passes `./gradlew assembleDebug test lint`, but **none of it has been exercised on a real call** — that's the next step (see the on-device test procedures in `docs/injection-routes.md`).

### Gemini-session worker (M2 bridge) — DONE, see `docs/gemini-live.md` for full detail
- `GeminiLiveSession` (file: `gemini/GeminiLiveSession.kt`) is the real OkHttp WebSocket client. `Config.GEMINI_MODEL_ID`/`GEMINI_MODEL_FALLBACK` verified live against the real API (both present in this key's model list; primary confirmed working via `GeminiLiveSmokeTest`).
- **Wire gotcha for whoever touches this next**: the real Live API sends every server message as a **binary** WS frame, not text. `GeminiLiveSession` implements both `onMessage(String)` and `onMessage(ByteString)` — if you ever replace/refactor the listener, keep the `ByteString` overload or the client will silently receive nothing and only the watchdog will tell you something's wrong.
- Setup message shape, VAD modes (`VadMode.GEMINI_VAD` / `LOCAL_VAD`), `interrupt()` semantics, and the `bn-BD` → `bn-IN` languageCode deviation are all documented in `docs/gemini-live.md`.
- Watchdog (spec §4.4, 8s) is implemented **inside** `GeminiLiveSession` itself (deviation from this note's original suggestion of the orchestration layer) — see `docs/gemini-live.md` for why.
- `TranscriptRecorder` (file: `gemini/TranscriptRecorder.kt`) writes `turns` rows per completed turn and accumulates cost onto the `calls` row via `CostModel`; it also detects the `[HANGUP]` token and exposes `hangupRequested: SharedFlow<Unit>` — this is prompt-domain logic kept out of `GeminiLiveSession` on purpose.
- **Not done (next milestone's job)**: wiring `LiveSession`/`TranscriptRecorder`/`SystemPromptBuilder` into `service/`, resampling `AudioOut` (24 kHz) down to 8 kHz before `Injector.write()`, resampling captured audio up to 16 kHz before `sendAudio`, and actually driving `sendActivityStart`/`sendActivityEnd` from the audio pipeline's VAD gate. `sendAudio` still just expects pre-resampled 16 kHz PCM16 mono ~100 ms chunks handed to it.

## Known deviations / open items from the spec

- `CAPTURE_AUDIO_OUTPUT` / `MODIFY_PHONE_STATE` / etc. are declared in the manifest but are only functional once installed as a priv-app via the Magisk module — expected per spec §5, not a bug.
- Gemini model id and Live API pricing figures in `callbridge-spec.md` §2/§9, marked "verify" in the spec, are now verified (2026-09-12) against live docs and a real API call — see `docs/gemini-live.md`. Both match the spec's placeholder values exactly.
- No on-device testing was possible during the M0 build (no phone attached). All acceptance was via `./gradlew assembleDebug`/`test`/`lint` and `bash -n` on the install script.

## Health knowledge tool (worker, 2026-09-12)

New `knowledge/` package (`HealthKnowledge`, `KnowledgeAnswer`, `ExaKnowledge`, `OpenAiKnowledge`,
`CompositeHealthKnowledge`) + `gemini/HealthPromptBn.kt` for the health-only demo persona. Not
wired into `GeminiLiveSession`/`BridgeSession` yet — see `docs/gemini-tools.md`'s "Wiring steps"
section for the next worker's exact TODOs (add `tools` to setup, add `ToolCall`/
`ToolCallCancelled` events, add `sendToolResponse`, hook `CompositeHealthKnowledge` into the
orchestration layer).

- `ExaKnowledge`: `POST https://api.exa.ai/search`, `x-api-key` header, `includeDomains` restricted
  to who.int/nhs.uk/mayoclinic.org/medlineplus.gov/icddrb.org/dghs.gov.bd, top 3 highlights.
  Verified live: real paracetamol-dosing query returned NHS/Barnsley-NHS/CEM-Scotland-NHS results
  in ~1.9s.
- `OpenAiKnowledge`: `gpt-4o-mini` (verified present via `GET /v1/models` on this project's key;
  `gpt-5-mini`/`gpt-5-nano` also present but `gpt-5-mini` burned ~1600 hidden reasoning tokens on
  an identical prompt in a live timing check — `gpt-4o-mini` returns a full answer in ~2.2s with
  no reasoning overhead, safer against the 6s per-provider timeout). System prompt: concise
  evidence-based guidance, ≤120 words, explicit red-flag flagging, never a definitive diagnosis.
- `CompositeHealthKnowledge`: Exa first, OpenAI fallback on error/empty/exception, never throws
  (returns a "no information available" `KnowledgeAnswer` if both fail or are disabled).
  `fromKeys(exaKey, openAiKey)` factory disables a provider whose key is blank.
- `BuildConfig.EXA_API_KEY` / `BuildConfig.OPENAI_API_KEY` added in `app/build.gradle.kts`,
  sourced from `local.properties` the same way `GEMINI_API_KEY` already is.
- Gemini Live tool-calling wire protocol verified live end-to-end (setup `tools` ->
  `toolCall` -> `toolResponse` -> audio + `turnComplete`) against `models/gemini-3.1-flash-live-preview`
  — full JSON shapes, the "model won't call the tool without an explicit systemInstruction telling
  it to" gotcha, and the wiring steps are all in **`docs/gemini-tools.md`**.
- Tests: `knowledge/ExaKnowledgeTest`, `knowledge/OpenAiKnowledgeTest`,
  `knowledge/CompositeHealthKnowledgeTest` (MockWebServer, 18 cases total) + live-gated
  `gemini/LiveToolCallSmokeTest` (same `-PliveSmoke=true` convention as `GeminiLiveSmokeTest`).
  All pass; `./gradlew testDebugUnitTest assembleDebug lint` green.
