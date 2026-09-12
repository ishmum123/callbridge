# CallBridge — session handoff (2026-09-12, 15:05, demo day)

Authoritative running state. Read this + `docs/STATUS.md` + `docs/gemini-live.md` + `docs/gemini-tools.md` + `docs/injection-routes.md` before continuing. Spec is `callbridge-spec.md` (farming helpline); **the demo scope has pivoted to a Bangla HEALTH helpline** (user decision 2026-09-12).

## Where we are (one paragraph)

Full pipeline runs end-to-end on the phone: GSM call auto-answered → caller audio captured (VOICE_DOWNLINK, 8 kHz) → Gemini Live (`gemini-3.1-flash-live-preview`, voice Sulafat, bn-IN) → reply resampled 24→16 kHz → injected into the call via **Route B (AudioTrack preferred device TYPE_TELEPHONY), proven audible on the far phone**. First real conversation happened at 14:37 (round-trip 0.4–1.3 s). Health-only prompt + `lookup_health_info` tool (Exa → OpenAI fallback) + per-caller **patient profile** (Gemini REST summarizer after each call, "Patients" screen in the app) are merged and installed. The remaining problem is **turn-taking on a real GSM line**: line echo of our own injected audio + venue noise gets read as caller speech.

## Demo build state (installed on phone, `main` @ HEAD, pushed)

`Config.kt` demo switches (all set for the demo):

| Switch | Value | Why |
|---|---|---|
| `injectorRoute` | `TELEPHONY_TX` | Route B proven on device |
| `vadMode` | `GEMINI_VAD` | server-side VAD, user decision ("optimisation later") |
| `HALF_DUPLEX` | `true` | caller audio NOT sent while model speaks + `activityHandling=NO_INTERRUPTION`; kills echo-triggered barge-in. Cost: no barge-in |
| `WATCHDOG_FATAL` | `false` | 8 s response watchdog logs instead of hanging up (a stalled turn killed a live call at 15:02) |
| `HEALTH_DEMO` | `true` | `HealthPromptBn` + tool; `false` = original farming prompt, no tool |
| `ANSWER_UNREGISTERED_CALLERS` | `true` | answer everyone |

Gemini VAD tuning in `WireMessages.AutomaticActivityDetection`: start sensitivity HIGH, end HIGH, prefix 300 ms, silence 600 ms.

Setup timeout raised 10→20 s (`GeminiLiveSession.setupTimeoutMs`) for slow venue Wi-Fi.

## Live-call log (what each attempt taught us)

| Time | Result | Cause / lesson |
|---|---|---|
| 14:04 | Tone test: 1 kHz heard on far phone | **Route B works.** Capture returns real 8 kHz audio |
| 14:30 | Setup timeout 10 s | **Mobile data is suspended during a GSM call on this phone** (no VoLTE/IMS). Wi-Fi is mandatory during calls |
| 14:35 | DNS fail instantly | Hotspot phone was the *calling* phone; it lost data when it dialled. Call from a different phone than the hotspot |
| 14:37 | **First working conversation**, RTT 0.4–1.3 s | Local VAD barge-in too trigger-happy: every "জী/হ্যাঁ" restarted the greeting |
| 14:38, 14:54 | Setup timeout | Flaky hotspot / venue guest Wi-Fi (500–1200 ms pings). Network, not code |
| 14:57 | Greeting "কেমন আছেন?" cut at 640 ms, then silence | Gemini VAD saw `Interrupted` = echo of our own audio on the downlink. → HALF_DUPLEX |
| 15:01 | Full greeting heard, then watchdog killed the call after 8 s | Model turn stalled (or never got an end-of-speech); watchdog was fatal. → `WATCHDOG_FATAL=false`, VAD start sensitivity back to HIGH |
| 15:03+ | **untested** — current build | Next call decides |

## Debugging playbook (phone attached over USB)

```
# arm before the call; prints bridge/gemini/summarizer lines after it ends
adb logcat -c; adb logcat -s BridgeSession GeminiLiveSession ProfileSummarizer TelephonyTxInjector VoiceCallCapture
# transcript of latest call
adb exec-out cat /data/data/bd.callbridge/databases/callbridge.db{,-wal,-shm} > … ; sqlite3: select * from turns where callId=(select max(id) from calls)
# debug hooks (debug build only, component target REQUIRED)
adb shell am broadcast -n bd.callbridge/.debug.DebugInjectReceiver -a bd.callbridge.DEBUG_INJECT --es route TELEPHONY_TX --ei seconds 5
adb shell am broadcast -n bd.callbridge/.debug.DebugInjectReceiver -a bd.callbridge.DEBUG_CAPTURE --ei seconds 5
adb shell am broadcast -n bd.callbridge/.debug.DebugInjectReceiver -a bd.callbridge.DEBUG_SEED_CALL --es number 01700000099
adb shell am broadcast -n bd.callbridge/.debug.DebugInjectReceiver -a bd.callbridge.DEBUG_SUMMARIZE
```
Bisection so far: TTS→injection ✅, capture ✅, STT ✅ (14:37 transcripts), network ✅ when on a good hotspot, **turn-taking ❌ (open)**. Key log lines: `phase=ACTIVE` (socket up), `server Interrupted` (VAD thought caller spoke), `round-trip caller-SpeechEnded -> first model audio`, `Watchdog: … non-fatal`.

If half-duplex still stalls: check whether Gemini ever emits a caller transcript row (`role=CALLER` in `turns`) — if not, caller audio isn't crossing the VAD threshold: try `startOfSpeechSensitivity=HIGH` + `silenceDurationMs=400`, or fall back to `vadMode=LOCAL_VAD` with a min-speech-duration guard before barge-in (not yet implemented).

## Milestones

| | State |
|---|---|
| M0 dialer | Done, verified |
| M1a capture | Done, verified on device |
| M1b injection | **Done, Route B verified on device** (`docs/injection-routes.md`) |
| M2 Gemini | Done, live-verified incl. greeting text turn + tool call round trip |
| M3 wire-up | Done, Opus-reviewed twice; all round-1 findings fixed; round-2 profile findings fixed. **Not fixed:** N2 `suppressStaleAudio` latch risk (moot under HALF_DUPLEX), m3 foreground service never stopped, n6/n8/n9 minors |
| Health demo | Prompt + tool wired (unit-tested; tool round trip verified live via raw socket, NOT yet through `GeminiLiveSession` on a real call) |
| Patient profile | Done; real migration 1→2; summarizer live-verified; Patients screen works (seeded + real call) |
| M4 pilot | Parked; demo first |

## Network facts (critical for any live run)

- Phone: SM-G781B, LineageOS 23.2 / Android 16, Robi SIM, **GSM voice → mobile data suspended during calls**. Wi-Fi required.
- Good: Pixel hotspot (`Pixel_3809`) when the hotspot phone is NOT the caller. Bad: venue `BYLC-GUEST` (0.5–1.2 s RTT).
- After any reboot: `adb root && adb remount && adb shell stop && adb shell start` (priv-app overlay is lost). Debug APK via `adb install -r` keeps the priv perms once granted.

## Keys / secrets

`local.properties` (gitignored): `GEMINI_API_KEY`, `OPENAI_API_KEY`, `EXA_API_KEY` → baked into `BuildConfig` of the debug APK. All three were pasted in chat: **rotate after the demo.**

## Immediate next steps

1. Test call on the 15:03 build (half-duplex + non-fatal watchdog). Read logs per playbook.
2. If turn-taking works: run the demo script — health question → tool call → non-health question (refusal) → goodbye ("আল্লাহ হাফেজ, ভালো থাকবেন।" triggers hangup) → open Status → Patients.
3. After demo: restore barge-in properly (LOCAL_VAD with min-speech guard, or echo suppression), make watchdog fatal again with the "line problem" clip (`res/raw/line_problem_24k.wav`, already committed, not yet played anywhere), stop the foreground service per call, rotate keys, update `docs/STATUS.md` health/tool sections (tool-wire worker skipped docs under deadline).
