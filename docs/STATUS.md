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
| M3 — Wire-up | **Code-complete, unit-tested. Untested on a real device/call** — see below. |
| M4 — Shop pilot | Pending. |
| Patient profile (demo, not in spec) | Code-complete, unit-tested + live-smoke-tested against the real Gemini REST `generateContent` endpoint. See section below. |

## M3 — wire-up (this build)

Composes capture -> Gemini Live -> injector into the active call. New files: `service/BridgeSession.kt`
(the per-call composition object) and `service/BridgeSessionManager.kt` (the singleton that owns
at most one `BridgeSession`, implements the new `call.CallSessionCoordinator` interface, and is
`CallController`'s hook into all of this).

**Decisions:**
- **VAD mode: `VadMode.LOCAL_VAD`.** The audio pipeline's own `VadGate` (M1a) already exists
  specifically to drive barge-in; `LOCAL_VAD` is what lets `sendActivityStart`/`sendActivityEnd`
  and `interrupt()` actually do something (`GEMINI_VAD` makes `interrupt()` a no-op and gives no
  manual turn boundary to hang a greeting off). No on-device A/B latency test between the two
  modes has been run (needs a real call — see `docs/gemini-live.md`'s open item).
- **Rates:** capture path needs no extra resampling before `sendAudio` — `AudioPipeline`/
  `VoiceCallCapture` already emit `Config.CAPTURE_SAMPLE_RATE_HZ` (16 kHz) mono PCM16 in ~100 ms
  chunks, which is exactly `LiveSession.sendAudio`'s contract. Gemini's `AudioOut` (24 kHz) is
  resampled once, with a single per-call `Resampler(24000, 16000)` instance (kept stateful across
  chunks for continuity), down to 16 kHz mono before `Injector.write()` — matches `Injector`'s
  documented contract ("mono PCM16 input at `Config.CAPTURE_SAMPLE_RATE_HZ`"); `TelephonyTxInjector`
  itself upmixes to stereo internally when it can open its preferred 16 kHz/stereo track.
- **Greeting:** `LiveSession` has no "speak first"/text-turn API (session is audio-modality only).
  Greeting = existing system-prompt instruction ("introduce yourself briefly at the start") +
  an empty `activityStart`/`activityEnd` pair sent immediately after `setupComplete`, which under
  manual VAD is the turn-boundary signal that makes the model generate its first response with
  nothing said yet.
- **Hangup mechanism (current, from M2):** `TranscriptRecorder.hangupRequested` (fuzzy match
  against the spoken Bangla closing phrase in the ASR'd output transcript — the `[HANGUP]`-token
  approach in spec §6 was superseded in M2, see `docs/gemini-live.md`). `BridgeSession` also treats
  `LiveSession.terminalState` going `Failed` (watchdog/socket death) the same way — both paths call
  back into `CallController.hangUp()` via an injected `onHangupRequested` lambda, never directly.
- **Teardown:** `BridgeSession.stop(reason)` is idempotent (mutex-guarded `stopped` flag), cancels
  the four/five collector jobs, then `injector.flush()+close()`, `liveSession.close()`,
  `transcriptRecorder.finish(reason)` — each wrapped in `runCatching` so one failing step doesn't
  skip the rest. `BridgeSessionManager` wraps `CallController`'s `onCallActive`/`onCallEnded`/
  `onServiceDestroyed` callbacks in `runCatching` too (brief requirement: never crash the
  InCallService).
- **Status wiring:** `BridgeSessionManager.status: StateFlow<BridgeStatus>` (phase, caller number,
  socket-open, injector route, last output-transcript line, end reason) — `StatusActivity` now
  collects it in `onCreate` (`lifecycleScope`) instead of only rendering static placeholders.
  Calls-today/cost-today stay TODO (need a `Flow` over `CallDao.observeCallsSince`/
  `observeCostSince`, out of this milestone's scope).
- **Logging:** tag `BridgeSession` — phase transitions (OPENING/ACTIVE/ENDING/ENDED), the
  `RouteProbe` + (`TelephonyTxInjector`-specific) `getRoutedDevice()?.type` right after `open()`,
  barge-in triggers, and the caller-`SpeechEnded` -> first-model-`AudioOut` round-trip in ms (the
  <1.5s target from `docs/HANDOFF.md`'s next-steps list).
- `CallController` gained one new optional constructor param, `sessionCoordinator:
  CallSessionCoordinator?` (default null, so every existing M0/M1/M2 test/caller is unaffected),
  and three call sites (`onTelecomCallActive`, `onCallRemoved`, `onServiceDestroyed`) that notify
  it, each wrapped in `runCatching`. `CallStateMachine` was **not** touched — no new states needed.
- `CallBridgeApp` wires `BridgeSessionManager` as `CallController`'s coordinator (constructed
  first, `attach()`ed back to the controller after, since the manager also needs to call
  `CallController.hangUp()`).

**Tests** (`app/src/test/java/bd/callbridge/service/BridgeSessionTest.kt`, 7 tests, all against
fakes — `FakeLiveSession`/`FakeInjector` plus the real `TranscriptRecorder` with the same in-memory
DAO fakes `TranscriptRecorderTest` uses): AudioOut resample+write path, barge-in interrupt+flush
(and the negative case — no interrupt when the model isn't speaking), hangup-phrase ->
`onHangupRequested`, session-`Failed` -> `onHangupRequested`, idempotent `stop()`, and the greeting
kick. `./gradlew testDebugUnitTest assembleDebug lint` all green (87 unit tests total, 0 failures).

**Untested on device** (needs a real call — the actual milestone-deciding step, per
`docs/HANDOFF.md`): whether `VoiceCallCapture` really gets caller audio, whether `TelephonyTxInjector`
routes to `TYPE_TELEPHONY` and is actually audible on the far phone, the greeting-kick's real
latency and whether the model reliably speaks first with it, and the caller-speech -> first-audio
round-trip time against the <1.5s target. **Recommended on-device verification steps** (in order):
1. Install the priv-app build, place a real call, confirm in logcat (`grep BridgeSession`) that
   `phase=ACTIVE`, the `RouteProbe` line, and `routedDeviceType` appear.
2. Confirm the greeting is heard first on the far phone within ~1-2s of pickup.
3. Speak a farming question in Bangla; confirm a reply is heard, check the logged round-trip ms.
4. While the model is replying, speak over it; confirm it stops (barge-in) and the caller is heard
   again promptly.
5. Say "না" / "শেষ করেন" after a reply's understanding-check question; confirm the model speaks the
   closing phrase and the call actually hangs up (Telecom disconnects, `CallStateMachine` returns
   to `IDLE`).
6. Check the `turns` table (`calls`/`turns` in the Room DB) has plausible caller/assistant text and
   a non-zero `estCostUsd` after the call ends.

## M3 — Opus code-review fixes (this build, on top of the initial M3 commit)

An Opus review of the first M3 commit found 2 blockers + 9 majors, all fixed here (13 unit tests
in `BridgeSessionTest`, up from 7; full findings/verdicts in the PR/commit history):

- **B1 (double hangup + leaked job):** `requestHangup` now computes `shouldHangUp` inside the
  mutex and only calls `onHangupRequested()` when true (was: `return@withLock` only skipped the
  lambda, not the call after it) — closing-phrase + terminal-`Failed` racing each other now call
  `onHangupRequested` exactly once. Its launched job is tracked in `jobs` so `stop()` cancels/joins
  it too, instead of it surviving to potentially disconnect a later call.
- **B2 (greeting armed the watchdog):** replaced the empty `activityStart`/`activityEnd` greeting
  kick with `LiveSession.sendTextTurn(text)` (new: `WireMessages.ClientContentEnvelope`,
  implemented in `GeminiLiveSession`, deliberately never arms the response watchdog). **Verified
  live** against the real Gemini Live API in `GeminiLiveSmokeTest` (`-PliveSmoke=true`): sending the
  Bangla greeting trigger produced `AudioOut` + `TurnComplete` within the 8s budget, no `Error`
  event — see that test's `system-out` for the actual event sequence.
- **M1 (ordering):** `AudioPipeline` gained a single ordered `events: Flow<PipelineEvent>` (sealed
  `Chunk`/`Vad`); `chunks` is now derived from it. `BridgeSession` consumes only `events` in one
  coroutine, so `sendActivityStart`/`sendAudio`/`sendActivityEnd` happen in strict encounter order
  — the tail-flush chunk is now emitted *before* the `SpeechEnded` event that would otherwise race
  it. `vadEvents`/`chunks` stay as separate flows too, for M1a's own tests.
- **M2 (rate mismatch):** `Injector` gained `openSampleRateHz: Int?` (negotiated post-`open()`,
  e.g. `TelephonyTxInjector`'s 16kHz-stereo-or-8kHz-mono fallback). The output resampler is now
  built from `injector.openSampleRateHz`, not a hardcoded 16kHz, and cached per (inputRate,
  outputRate) pair (m6) instead of rebuilt per chunk.
- **M3 (stale audio):** a `suppressStaleAudio` flag, set on barge-in and cleared on
  `Interrupted`/`TurnComplete`, drops any further `AudioOut` frames from the turn that was just
  barged into.
- **M4 (teardown ordering):** `stop()` now cancels `startJob` + every collector job and **joins**
  them (`joinAll()`) before touching `injector`/`liveSession`, instead of firing cancellation and
  immediately closing shared resources those jobs might still be mid-write on.
- **M5 (drain logic):** the closing-phrase hangup path now waits (`withTimeoutOrNull`) for the next
  `TurnComplete`/`Interrupted` on `liveSession.events` — not a fixed sleep, and not on the first
  transcript delta that happens to contain the phrase — before hanging up, so the model's spoken
  goodbye finishes. `stop()` itself no longer has any artificial delay.
- **M6 (BridgeSessionManager races):** `BridgeSession.start()` now returns promptly (`open()` runs
  in a background job the session owns), so `BridgeSessionManager` assigns `currentSession` before
  calling `start()` rather than after it returns, and never holds `sessionMutex` across the network
  open. `BridgeSession.stop()` cancels the in-flight open job directly instead of waiting out a 10s
  setup timeout. A `pendingJob` in the manager covers the last sliver (call ending during the
  local/DB work in `buildSession`, before `currentSession` is even assigned).
- **M7 (subscribe-before-open):** `liveSession.events`/`terminalState` collectors are now launched
  before `liveSession.open()` is called, matching `LiveSession.open`'s documented contract.
- **M8 (blocking write):** `Injector.write()` runs inside `withContext(Dispatchers.IO)`, not on the
  shared event-collector dispatcher.
- **M9 (duplicate activityStart):** barge-in now only calls `interrupt()` (which itself sends the
  manual activityStart under `LOCAL_VAD`) — it no longer also calls `sendActivityStart()` directly.
- **Minors:** mutable cross-coroutine flags are `@Volatile`; `BridgeSessionManager` now passes the
  real call direction (`isOutgoing`) instead of hardcoding `INBOUND`; `StatusActivity` uses
  `repeatOnLifecycle(STARTED)` and always updates the phase text, not just when a transcript line
  arrives.
- **Declined, with reasons:**
  - *"Stop `BridgeForegroundService` when the session ends"* — not done. That service is
    started at boot / from `StatusActivity` specifically to keep the CPU/Wi-Fi awake so the *next*
    call can be answered; stopping it after every call would drop that between calls, which looks
    like a regression, not a fix. Left `BridgeForegroundService.stop()` available as a utility but
    unused.
  - *"Default `Config.injectorRoute` to `TELEPHONY_TX`, Route B is confirmed routable on-device"* —
    not done. `docs/HANDOFF.md` (same day) is explicit that Route B is **not yet proven on a real
    device** ("the milestone-deciding test, nobody has run it yet"); flipping the default and
    documenting it as "confirmed" would assert live-state nobody here has actually probed. Left
    `Config.injectorRoute = NOOP`; flip it once the on-device test in `docs/HANDOFF.md`/this file's
    verification steps above has actually been run.

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

## Patient profile (demo feature, not in the original spec)

A per-caller clinical profile for health managers, built up call-over-call. Not wired into the
live call flow — that one line is left for whoever owns `CallController`/`BridgeSession` (M3).

- **Store**: `PatientProfileEntity` (keyed by phone number) + `ProfileUpdateEntity` (per-call
  delta history) in `store/Entities.kt`; `PatientProfileDao`/`ProfileUpdateDao` in
  `store/dao/Daos.kt`; `ProfileRepository`/`RoomProfileRepository` in `store/ProfileRepository.kt`.
  DB bumped to `version = 2` with a real `MIGRATION_1_2` (`store/CallBridgeDatabase.kt`) that
  creates `patient_profiles`/`profile_updates` (+ its `number` index) with SQL copied verbatim
  from the exported schema (`app/schemas/.../2.json`); `fallbackToDestructiveMigration()` stays
  registered only as a backstop for a *future* version bump that ships without its own migration.
  Verified by `store/CallBridgeMigrationTest.kt` (Robolectric — builds a real v1 SQLite file by
  hand from `1.json`'s SQL, sets its `user_version` pragma, opens it through `CallBridgeDatabase`
  with `MIGRATION_1_2` registered so Android's real `onUpgrade(1,2)` path runs, then asserts prior
  `callers` data survived and the new tables are fully queryable). List-of-string fields are
  stored as JSON via `Converters` (`fromStringList`/`toStringList`).
- **Summarizer**: `profile/ProfileSummarizer.kt`. `suspend fun onCallFinished(callId: Long, force:
  Boolean = false)` loads the call's `turns` + any existing profile, calls Gemini's REST
  `generateContent` endpoint (`Config.GEMINI_SUMMARY_MODEL_ID = "gemini-2.5-flash"` — **not** the
  Live API/WebSocket, 30s `callTimeout`) with `responseMimeType: application/json` + a
  `responseSchema` matching the entity, and asks the model for the *complete merged* profile plus
  a `deltaSummary` for the per-call history row. Runs on `Dispatchers.IO`, never throws to the
  caller (`CancellationException` is rethrown, everything else caught); an empty transcript is
  skipped entirely; any HTTP/parse failure or a `finishReason=MAX_TOKENS` truncation is logged
  (length + 80-char preview only, never a raw body/model-output dump — see PII note below) and
  recorded as `PatientProfileEntity.lastError`, leaving the rest of the profile untouched.
  Response parsing concatenates *all* of `candidates[0].content.parts[*].text` (not just
  `parts[0]`), and logs `finishReason` on every call.
  - **Accumulate semantics enforced in code, not left to the model**: `chronicConditions`,
    `medications`, `allergies`, `riskFlags`, `adviceGiven` are a case-insensitive union of the
    existing profile + the model's output (order preserved, deduped) — so an empty array back
    from the model can never silently drop prior facts. `currentSymptoms`,
    `followUpNeeded`/`followUpNote`, `summaryBn`/`summaryEn`, and the scalar identity fields
    remain model-authoritative (latest call wins / non-null overrides).
  - **Idempotent per callId, `callCount` derived not incremented**: a `profile_updates` row
    existing for a `callId` short-circuits `onCallFinished` (no HTTP call, profile untouched)
    unless `force = true`. `callCount` is computed as `count(profile_updates for number) + 1`
    (this call's prior row, if any, is deleted first) rather than `existing.callCount + 1`, so a
    forced re-summarize of the same call replaces its update row instead of double-counting.
  - **PII in logs**: phone numbers are masked via `util/Redact.kt` (`Redact.phone("+8801700000784")
    -> "+88017…784"`); HTTP bodies and raw model output are never logged verbatim, only
    length + an 80-char preview.
  Verified against the real API: `ProfileSummarizerSmokeTest` (gated exactly like
  `GeminiLiveSmokeTest` — `-PliveSmoke=true` + `GEMINI_API_KEY` in `local.properties`) got back a
  correctly-parsed profile with Bangla + English summaries from a real Bangla fever/cough
  transcript.
- **Wiring left for the orchestrator** (not done by this worker — another worker owns
  `CallController`/`BridgeSession`): call this one line right after a call's
  `TranscriptRecorder.finish(endReason)` completes:
  ```kotlin
  (applicationContext as CallBridgeApp).profileSummarizer.onCallFinished(callId)
  ```
  Fire-and-forget from a non-blocking scope is fine. Pass `force = true` only for an intentional
  re-summarize (e.g. the debug broadcast below) — the normal path should rely on the default
  `force = false` idempotency guard.
- **Debug path** (exercise without a live call): `DebugInjectReceiver` gained two actions
  (`app/src/debug/java/bd/callbridge/debug/DebugInjectReceiver.kt`,
  `app/src/debug/AndroidManifest.xml`):
  ```
  adb shell am broadcast -a bd.callbridge.DEBUG_SEED_CALL --es number 01700000099
  adb shell am broadcast -a bd.callbridge.DEBUG_SUMMARIZE
  adb shell am broadcast -a bd.callbridge.DEBUG_SUMMARIZE --el callId 1
  adb shell am broadcast -a bd.callbridge.DEBUG_SUMMARIZE --el callId 1 --ez force false
  adb logcat -s InjectTest
  ```
  `DEBUG_SEED_CALL` inserts a finished fake call with a realistic 6-turn Bangla transcript (fever
  + cough, then the same caller asking about iron tablets as a pregnant woman). `DEBUG_SUMMARIZE`
  runs the summarizer for a given `callId` or the most recent call if omitted, defaulting to
  `force=true` (that's the point of the broadcast — pass `--ez force false` to exercise the
  skip-if-already-summarized path instead).
- **UI**: `ui/PatientsListActivity.kt` (list of profiles — name/number, last call, risk-flag
  chips, follow-up badge; reachable from `StatusActivity`'s new "Patients" button) and
  `ui/PatientDetailActivity.kt` (all fields grouped: Summary / Conditions & symptoms /
  Medications & allergies / Risk flags / Advice & follow-up / Call history with per-call delta).
  Plain Views + view binding + Material components (`MaterialCardView`, `Chip`), matching the
  rest of the app's UI toolkit — no new dependency added. Neither activity logs anything today,
  so there was nothing to retrofit with `Redact` there; kept in mind for whoever adds logging.
- **Tests**: `profile/ProfileSummarizerTest.kt` (MockWebServer — empty-transcript skip, successful
  merge + delta row, HTTP failure records `lastError` without touching existing fields, unparsable
  JSON handled the same way, empty-array accumulate retention, case-insensitive union dedupe,
  idempotent no-op re-run, force re-run replaces instead of duplicates, `callCount` derived across
  two distinct calls, multi-part response concatenation, `MAX_TOKENS` recorded as an error,
  `CancellationException` propagates), `store/PatientProfileDaoTest.kt` (Robolectric, same pattern
  as `CaptureWavDumperTest` — upsert/find round-trip including list columns, REPLACE-on-conflict,
  update-history ordering), `store/CallBridgeMigrationTest.kt` (Robolectric, real v1->v2 migration
  — see Store section above), `profile/ProfileSummarizerSmokeTest.kt` (real-API gated smoke test).

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
