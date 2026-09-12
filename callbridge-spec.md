# CallBridge — Rooted Android → Gemini Live PSTN Gateway

Pilot for a rural AI helpline in Bangladesh. Anyone with any phone dials a normal mobile number; a rooted Galaxy S20 FE answers, bridges the GSM call audio to Gemini Live over WebSocket, and speaks the reply back into the call. Mobile-recharge shops are the physical front (registration, trust, cash).

This doc is for a coding agent. Build in milestone order. M1 (injection) is the only hard problem; everything before it is standard Android, everything after it is plumbing.

---

## 1. Goals / non-goals

**Goals**
- Inbound call on the phone's SIM → auto-answer → live Bangla conversation with Gemini → hang up.
- Missed-call → callback, so the caller pays nothing.
- One concurrent call. Second caller gets busy + SMS "we'll call you back".
- Per-caller profile (name, village, occupation) injected into the system prompt.
- Transcript per call stored for a later shopkeeper dashboard.
- Runs unattended, plugged in, on Wi-Fi, for days.

**Non-goals (pilot)**
- More than one simultaneous call.
- SIP/IPTSP, GSM gateway hardware.
- Own STT/TTS. Gemini Live does both ends.
- Any UI beyond a status screen and a log.

---

## 2. Hardware / software

- Phone: Samsung Galaxy S20 FE **SM-G781B**, Snapdragon 865 (`ro.hardware=qcom`), Magisk root.
- OS: One UI (Android 13 target; adjust if different — check `ro.build.version.release`).
- Language: Kotlin app + small NDK (C++) shim for the injection track.
- Model: Gemini Live, latest Flash Live model (verify exact model id in the Gemini API docs before coding). Audio in: 16 kHz PCM16 mono. Audio out: 24 kHz PCM16 mono.
- Backend (minimal): a tiny NestJS/Node service for caller profiles and transcript storage, or SQLite on the phone for the pilot. Start with SQLite; move to server when there's more than one phone.

---

## 3. Architecture

```
GSM call ◄──► Android Telephony
                    │
        ┌───────────┴────────────┐
        │  CallBridge (priv-app) │
        │                        │
        │  InCallService ─ call control (answer/reject/callback)
        │  Capture  ─ AudioRecord(VOICE_CALL) 8 kHz  ──► resample 16 kHz ──► VAD ──► WebSocket ──► Gemini Live
        │  Inject   ─ NDK AudioTrack(INCALL_MUSIC)  ◄── resample 8 kHz  ◄── audio chunks ◄────────┘
        │  Session  ─ system prompt + caller profile, barge-in, watchdog
        │  Store    ─ SQLite: callers, calls, transcripts
        └────────────────────────┘
```

Per call:
1. Ring → `InCallService.onCallAdded`.
2. Policy: if caller is in `callers` table → answer immediately. Else → reject on first ring, wait 2 s, call back (so it's free for them). On our outbound call connecting → same pipeline.
3. Open Gemini session with system prompt + profile.
4. Play a 1-second pre-recorded greeting clip locally (so there's no dead air while the socket warms up), then start streaming.
5. Loop: capture → VAD gate → send; receive → inject. Barge-in: on caller speech while playing, stop the AudioTrack, flush the output queue, send an interrupt to Gemini.
6. Hang-up (either side) or 10 s of silence after Gemini asked "anything else?" → close session, store transcript.

---

## 4. Components

### 4.1 Call control (no root needed)
- Register as default dialer: `TelecomManager.ACTION_CHANGE_DEFAULT_DIALER` + `InCallService` in manifest with `BIND_INCALL_SERVICE`.
- `Call.answer(VideoProfile.STATE_AUDIO_ONLY)`, `Call.reject()`, `Call.disconnect()`.
- Outbound: `TelecomManager.placeCall(Uri.fromParts("tel", number, null), extras)`.
- Get caller number from `Call.getDetails().getHandle()`.
- State machine: `IDLE → RINGING → (ANSWERED | REJECTED_FOR_CALLBACK) → ACTIVE → ENDED`. Only one call may be `ACTIVE`; a second incoming call while `ACTIVE` → reject + SMS via `SmsManager`.

### 4.2 Capture (root: priv-app)
- `AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, 8000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, buf)`.
- Needs `android.permission.CAPTURE_AUDIO_OUTPUT` (signature|privileged) → app must be in `/system/priv-app` with a privapp-permissions XML (§5).
- Verify it returns the **downlink** (caller's voice). If it returns a mix of both sides, that's fine for the pilot. If it returns only uplink, switch to `VOICE_DOWNLINK`.
- Resample 8 → 16 kHz (simple polyphase or `libsamplerate`; Oboe's resampler is also fine).
- VAD: WebRTC VAD (`libwebrtcvad` or the JNI port), mode 2, 20 ms frames. Send 200 ms of pre-roll before speech onset; stop sending 400 ms after speech ends. This cuts input tokens 30–50 % on rural calls.

### 4.3 Injection (root: the hard part)
Goal: play PCM into the call **uplink** so the caller hears Gemini.

**Route A — Qualcomm in-call music path (try first)**
1. Confirm HAL support:
   ```
   su -c 'grep -il incall /vendor/etc/mixer_paths*.xml'
   su -c 'grep -i -A8 "incall" /vendor/etc/mixer_paths_*.xml'
   su -c 'grep -i "TELEPHONY_TX\|incall" /vendor/etc/audio_policy_configuration.xml /vendor/etc/audio_platform_info.xml'
   ```
   Looking for a path named like `incall_music_uplink` / `incall-music-uplink` and an output device `AUDIO_DEVICE_OUT_TELEPHONY_TX`.
2. NDK shim: open a native `AudioTrack` with `AUDIO_OUTPUT_FLAG_INCALL_MUSIC` (value `0x8000` in `audio-base.h`), stream type voice call, 8 kHz or 16 kHz mono PCM16 (try 8 first; some HALs only accept 8/16 on this path). Link against `libaudioclient.so` via `dlsym` from a priv-app; expose `open()`, `write(pcm)`, `flush()`, `close()` over JNI.
3. If the path exists but the caller hears nothing: while a call is active, run `tinymix` and toggle any control containing `Incall_Music` / `Voice Tx` / `MultiMedia2` to 1; retest. Record the working set and apply them from the app at call start via `su -c tinymix ...`.
4. If the path stanza is missing from Samsung's XML: add it via a Magisk overlay of `mixer_paths_*.xml`, copying the stanza from a stock Snapdragon 865 device (Pixel 4a 5G, OnePlus 8 firmware dumps). Then repeat step 3.

**Route B — telephony TX device**
Set the AudioTrack's preferred device to the `TELEPHONY_TX` `AudioDeviceInfo` (`AudioTrack.setPreferredDevice`) from a priv-app with `MODIFY_AUDIO_ROUTING`. Cheaper than A if the device is exposed; test in 30 minutes before spending time on A.

**Route C — physical loopback (always works)**
Phone on speakerphone inside a padded box; USB-C audio dongle; a second cheap phone or a laptop runs the Gemini bridge, its speaker output goes to the dongle's mic input. Use only if A and B both fail after two evenings. Audio quality is worse; fine for a two-week shop pilot.

Deliverable of this component: `Injector` interface with A/B/C implementations, selected by a config flag.

### 4.4 Gemini session
- WebSocket to the Live API, ephemeral token minted by the backend (or API key on the phone for the pilot — rotate it).
- Setup message: model id, response modality audio, Bangla voice, system prompt (§6), VAD **disabled on Gemini's side** (we do our own gating) or enabled with our pre-roll — test both and pick the lower latency.
- Send 16 kHz PCM16 chunks of 100 ms.
- Receive audio chunks → resample 24 → 8 kHz → `Injector.write()`.
- Barge-in: our VAD fires while `Injector` is playing → `Injector.flush()` + send the Live API's interrupt/`activityStart` signal so Gemini stops generating.
- Transcript: request input and output transcription in the setup so we get text without a second STT pass. Store per turn.
- Watchdog: if no socket message for 8 s during an active call, or the socket closes → play local "লাইনে সমস্যা, আবার কল করছি" clip, hang up, schedule callback in 30 s, reopen a fresh session.

### 4.5 Storage (SQLite on phone, pilot)
```
callers(number PK, name, village, occupation, language_note, registered_by_shop, created_at)
calls(id, number, direction, started_at, ended_at, end_reason, input_seconds, output_seconds, est_cost_usd)
turns(id, call_id, role, text, t_ms)
```
Shopkeeper registers callers via a hidden screen in the app (number, name, village, occupation). Nothing else in the pilot.

### 4.6 Status screen
Big text: state, current caller number, socket status, injector route, calls today, est. cost today. Buttons: hang up, test injection (plays a tone into a live call), register caller.

---

## 5. Priv-app install (Magisk module)

```
module/
  module.prop
  system/priv-app/CallBridge/CallBridge.apk
  system/etc/permissions/privapp-permissions-callbridge.xml
  system/etc/sysconfig/callbridge.xml        (optional: allow-in-power-save)
```

`privapp-permissions-callbridge.xml`:
```xml
<permissions>
  <privapp-permissions package="bd.callbridge">
    <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
    <permission name="android.permission.MODIFY_PHONE_STATE"/>
    <permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
    <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
    <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
  </privapp-permissions>
</permissions>
```
Also request at runtime: `RECORD_AUDIO`, `READ_PHONE_STATE`, `CALL_PHONE`, `SEND_SMS`, `ANSWER_PHONE_CALLS`.

One UI settings to change once:
- Disable Bixby Voice, call "voice focus"/"adapt sound", call recording features.
- Battery: exempt the app from optimisation; disable "put unused apps to sleep".
- Keep screen off but CPU on: foreground service + partial wake lock + `WifiManager.WIFI_MODE_FULL_HIGH_PERF`.

---

## 6. System prompt (v0, Bangla)

Principles, not full text — write the actual prompt in Bangla:
- Identity: helpline for farmers, run via the local shop. Not a person.
- One idea per turn. After each idea, ask if it was understood. Never more than two sentences before a question.
- Short, plain, village Bangla. No English words unless the caller uses them.
- Unknown / risky (medicine doses, legal, money) → say who to go to (upazila office, pharmacy, the shop) rather than guess.
- End the call cleanly: "আর কিছু?" → if no → goodbye and hang up (emit a `[HANGUP]` token the app watches for in the output transcript).
- Inject: `caller: {name}, {village}, {occupation}`; `today: {date}`; `shop: {shop name}`.

Start with one vertical: crop prices / farming questions. Add health triage only after the first week's transcripts show demand.

---

## 7. Build order

**M0 — Dialer skeleton (½ day)**
Default dialer app, auto-answer, hang-up, caller number on screen. Test with your own second phone.

**M1a — Capture (½ day)**
Magisk priv-app install. `AudioRecord(VOICE_CALL)` → write 8 kHz WAV to storage during a test call. Listen to it. This tells you real GSM audio quality and which sides you're getting.

**M1b — Injection (1–2 evenings; the gate)**
Route B → Route A → Route C. Success = a 1 kHz tone from the app is audible on the far phone. Keep a `tinymix` dump of the working state.

**M2 — Bridge (1 day)**
WebSocket client, resamplers, no VAD, no barge-in. First end-to-end conversation. Measure round-trip (end of caller speech → first audio back) — target < 1.5 s.

**M3 — Polish (1–2 days)**
VAD gating, barge-in, greeting clip, watchdog, missed-call callback, busy SMS, transcripts, status screen.

**M4 — Shop pilot (2 weeks)**
One shop, 20 registered farmers, one vertical. Read every transcript daily. Adjust prompt weekly.

---

## 8. Tests

- Unit: resamplers (round-trip a sine, check THD), VAD gate timing, state machine transitions.
- Bench: loopback test — play a known Bangla WAV into the *far* phone's mic, assert transcript contains expected words; assert injected tone is heard on the far phone.
- Soak: 50 automated callbacks to a test phone over a night; no crashes, no stuck `ACTIVE` state, memory flat.
- Field: 5 real rural callers before opening to the shop; note dialect misrecognitions.

---

## 9. Cost tracking

Log per call: seconds sent to Gemini (after VAD), seconds received. Estimate cost with current Live API audio prices (input ≈ $0.005/min, output ≈ $0.018/min at time of writing — verify) and show it on the status screen. Expect ৳3–8 per 3-minute call plus the SIM's voice tariff. If output seconds > 60 % of call length, tighten the prompt.

---

## 10. Open questions

- Which side(s) `VOICE_CALL` capture returns on this firmware — decides whether we need `VOICE_DOWNLINK`.
- Whether Samsung kept the `incall_music_uplink` stanza — decides Route A vs overlay.
- Gemini-side VAD vs ours: which gives lower round-trip on 8 kHz audio.
- Dialect: how badly Sylheti/Chittagonian degrade recognition — only field transcripts will say.
