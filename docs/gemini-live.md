# Gemini Live session (M2)

Implementation: `app/src/main/java/bd/callbridge/gemini/` (`GeminiLiveSession`, `WireMessages.kt`,
`AuthProvider`, `TranscriptRecorder`, `CostModel`, `SystemPromptBuilder`).

## Endpoint and message shapes (verified 2026-09-12)

Sources:
- https://ai.google.dev/api/live — full `BidiGenerateContent` message reference.
- https://ai.google.dev/gemini-api/docs/live-api/capabilities — capabilities guide (audio format,
  transcription, manual-VAD example).
- https://ai.google.dev/gemini-api/docs/pricing — audio pricing.
- Live verification: `GEMINI_API_KEY` from `local.properties`, real WebSocket round trip against
  both `gemini-3.1-flash-live-preview` and (as a probe) `gemini-2.5-flash-native-audio-preview-12-2025`.

Endpoint:
```
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>
```

Setup message (client -> server, once, right after connecting):
```json
{
  "setup": {
    "model": "models/gemini-3.1-flash-live-preview",
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "speechConfig": {
        "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}},
        "languageCode": "bn-IN"
      }
    },
    "systemInstruction": {"parts": [{"text": "..."}]},
    "inputAudioTranscription": {},
    "outputAudioTranscription": {},
    "realtimeInputConfig": {
      "automaticActivityDetection": {"disabled": true},
      "activityHandling": "START_OF_ACTIVITY_INTERRUPTS"
    }
  }
}
```
Server acks with `{"setupComplete": {}}`.

Audio in (16 kHz PCM16, ~100 ms chunks):
```json
{"realtimeInput": {"audio": {"data": "<base64>", "mimeType": "audio/pcm;rate=16000"}}}
```

Manual VAD signals (only sent when `automaticActivityDetection.disabled: true`):
```json
{"realtimeInput": {"activityStart": {}}}
{"realtimeInput": {"activityEnd": {}}}
```

Server content (audio out is 24 kHz PCM16, base64 in `inlineData.data`):
```json
{
  "serverContent": {
    "modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "<base64>"}}]},
    "inputTranscription": {"text": "..."},
    "outputTranscription": {"text": "..."},
    "interrupted": true,
    "turnComplete": true
  }
}
```
`goAway: {timeLeft: ...}` signals an imminent server-initiated disconnect.

**Important wire detail**: the real server sends every message as a **binary** WebSocket frame
(opcode 0x2), not text (opcode 0x1) — confirmed against the live endpoint (a Python
`websockets` client received `bytes`, and an OkHttp client that only implemented
`onMessage(String)` received nothing at all and hung until the watchdog fired). `GeminiLiveSession`
implements both `WebSocketListener.onMessage(String)` and `onMessage(ByteString)`, decoding the
latter as UTF-8 JSON — required, not defensive-only. Any future OkHttp-based Live API client
**must** implement the `ByteString` overload or it will silently receive nothing.

## VAD modes (spec §10)

- `VadMode.GEMINI_VAD`: `automaticActivityDetection.disabled = false`. Server does its own VAD;
  `sendActivityStart`/`sendActivityEnd` are no-ops; `interrupt()` is a no-op (server already
  detects the caller's speech and interrupts on its own, per
  `activityHandling: START_OF_ACTIVITY_INTERRUPTS`, the default).
- `VadMode.LOCAL_VAD`: `automaticActivityDetection.disabled = true`. Our own VAD gate (audio
  pipeline milestone) must call `sendActivityStart()`/`sendActivityEnd()` around caller speech.
  `interrupt()` sends `activityStart`, which under the default `activityHandling` cancels
  in-flight generation server-side (verified against https://ai.google.dev/api/live: *"If true,
  start of activity will interrupt the model's response (also called barge-in)"*).

No on-device A/B latency measurement was done in this milestone (that needs the audio pipeline +
injector wired end-to-end, M3's job); the mode is selectable per `GeminiLiveSession` constructor
argument for that future comparison.

## Language code deviation

Spec §6 asks for `bn-BD`. The Live API's documented language table only lists a base `bn` entry;
no `ai.google.dev` page found during verification listed a `bn-BD` or `bn-IN` variant explicitly.
`bn-IN` was used as `speechConfig.languageCode` (Google's long-standing Cloud Speech-to-Text/TTS
convention for Bengali is `bn-IN`; there is no `bn-BD` locale in any Google speech product this
worker could find) and the live smoke test confirms the server accepts it without error. Re-check
against `ai.google.dev/gemini-api/docs/live-api/*` if Google ships explicit Bangladesh Bangla
support later — this is the one field most likely to need revisiting.

## HANGUP token — architecture note

Spec §6 says the model emits a literal `[HANGUP]` token in the output transcript to end the call.
This is treated as **prompt/business logic**, not a generic Live API wire event: detection lives
in `TranscriptRecorder` (which watches accumulated `outputTranscription` text for
`SystemPromptBuilder.HANGUP_TOKEN` and exposes a `hangupRequested: SharedFlow<Unit>`), not as a
`LiveSessionEvent` on `GeminiLiveSession`. `GeminiLiveSession` stays a thin, model-agnostic
transport; call-ending logic based on prompt-specific tokens belongs one layer up. The call
orchestration layer (M3) should collect `TranscriptRecorder.hangupRequested` to trigger hangup.

## Watchdog

`GeminiLiveSession` owns its own watchdog (constructor arg `watchdogTimeoutMs`, default 8000ms per
spec §4.4), rather than pushing it to the orchestration layer as the M0 handoff note originally
suggested — keeping it inside the session made it directly unit-testable against a real
(short-timeout) MockWebServer connection without needing to fake the whole call-orchestration
layer. It recomputes `now - lastMessageAt` in a self-correcting loop (re-delays for the remaining
time each iteration) so it fires within roughly one scheduler tick of the true 8s boundary rather
than up to 2x late. Tests use a **short real timeout** (300ms) with real dispatchers rather than a
virtual-time `TestDispatcher`, since the behavior under test spans real OkHttp I/O threads that a
virtual clock doesn't control — this is a deliberate deviation from the brief's suggestion of a
"test dispatcher" and is documented here for that reason.

## Pricing (spec §9), verified 2026-09-12

Source: https://ai.google.dev/gemini-api/docs/pricing

`gemini-3.1-flash-live-preview` (same rate applies to the fallback
`gemini-2.5-flash-native-audio-preview-12-2025`):
- Audio input: $3.00 / 1M tokens ≈ **$0.005/min**
- Audio output: $12.00 / 1M tokens ≈ **$0.018/min**

`CostModel.estimateUsd(inputSeconds, outputSeconds)` implements exactly these two rates. Matches
the spec's placeholder numbers exactly — no change needed there. Re-verify before the shop pilot
in case pricing moves (these are all still "preview" models).

## Live smoke test result

`GeminiLiveSmokeTest` (skipped automatically when `GEMINI_API_KEY` is absent from
`local.properties`) opened a real session against the pinned model, sent 1s of 16 kHz silence, and
passed: `model=gemini-3.1-flash-live-preview events=[]` — setup acknowledged
(`{"setupComplete":{}}"`), no `Error` event, clean close. The fallback model was not exercised
(primary succeeded) since the retry path only fires when the primary fails; both models are
confirmed present in this key's model list (`GET v1beta/models`). Note: `watchdogTimeoutMs` is
raised to 30s in the smoke test only, since sending pure silence with local (manual) VAD and no
`activityStart`/`activityEnd` boundaries legitimately produces zero server messages for well over
8s — that's real Live API behavior, not a bug, and is exactly what the 8s production default is
meant to catch on a genuinely dead connection.

## Known gaps for M3

- No end-to-end wiring to `audio/` or `Injector` yet (out of this milestone's scope — see
  `docs/STATUS.md`).
- No reconnect-on-watchdog logic — `GeminiLiveSession` only *emits* `Error`/`Closed`; the M0
  handoff assigns actual reopening to the call-orchestration layer (M3).
- `EphemeralTokenAuth` is a stub; the pilot uses `ApiKeyAuth` with the raw on-device key per spec
  §4.4's pilot note.
- No on-device A/B latency test between `GEMINI_VAD` and `LOCAL_VAD` (needs the real audio
  pipeline).
