# Injection routes (M1b, spec §4.3/§7)

Goal: get PCM into the call **uplink** so the caller hears Gemini. Three routes, selected by
`bd.callbridge.Config.injectorRoute` via `bd.callbridge.audio.InjectorFactory`. Spec's own
build order is **Route B → Route A → Route C**; this doc keeps that order.

## Route B — `TelephonyTxInjector` (primary, real path)

**File:** `app/src/main/java/bd/callbridge/audio/TelephonyTxInjector.kt`

**Mechanism:** a plain `android.media.AudioTrack` with `AudioAttributes` usage
`VOICE_COMMUNICATION`, routed to the `TELEPHONY_TX` output device via
`AudioTrack.setPreferredDevice()`. Needs `MODIFY_AUDIO_ROUTING` (declared, priv-app only).
Prefers 16 kHz/stereo (matches the `Telephony Tx` device port and lets one `TestTone` buffer
work unmodified for both B and A); falls back to 8 kHz/mono if track construction fails at the
preferred format. Mono `Injector.write()` input is duplicated to both stereo channels
internally.

**What to look at in logcat:** tag `TelephonyTxInjector`. A successful open logs nothing (only
warnings on failure paths); call `probe()` from the status screen / test-injection button to
get a `RouteProbe` with `deviceFound`, `preferredDeviceSet`, and `routedDeviceId`.

**Expected failure modes:**
- `deviceFound = false` — no `AudioDeviceInfo` of `TYPE_TELEPHONY` in
  `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`. Per docs/hal-recon.md this device port exists
  in `audio_policy_configuration.xml` on the pilot phone, so this would mean the port isn't
  actually exposed to `AudioManager` (only declared in the policy XML) — worth confirming with
  `dumpsys audio` during a live call before spending more time on Route B.
- `deviceFound = true, preferredDeviceSet = false` — `setPreferredDevice()` returned false;
  usually means the app lacks `MODIFY_AUDIO_ROUTING` at runtime (priv-app permission grant
  didn't take — check `dumpsys package bd.callbridge | grep MODIFY_AUDIO_ROUTING`) or the
  track's attributes don't match a device the policy will actually route non-call streams to.
- `preferredDeviceSet = true` but the far phone hears nothing — the preferred device hint was
  accepted but audio policy still didn't route there in practice (`getRoutedDevice()` after
  `play()` will show where it actually landed — compare its `id`/`type` against the requested
  device's).
- `AudioTrack` construction fails at both sample rates — check `getMinBufferSize()` return
  value in the warning log; a negative value means the HAL rejected the format outright.

**On-device test procedure:**
1. Install the priv-app build (`scripts/install-privapp.sh`), start a real call in from a
   second phone.
2. From the status screen, tap "test injection" (calls `Injector.playTestTone()`, wired to
   whatever `Config.injectorRoute` currently selects — set it to `TELEPHONY_TX` first).
3. Listen on the second (far) phone for a clean 2-second, 1 kHz tone.
4. If silent, pull `probe()`'s detail string from the status screen / logcat and match against
   the failure modes above.
5. Record the outcome (and, if it worked, the exact `AudioDeviceInfo.id`/type) at the bottom of
   this doc.

## Route A — `IncallMusicInjector` (research finding: not implementable from an app process)

**Files:** `app/src/main/java/bd/callbridge/audio/IncallMusicInjector.kt`,
`app/src/main/cpp/audioclient_probe.cpp`.

docs/hal-recon.md confirms the HAL side is fully present — `incall_music_uplink` mixPort with
`AUDIO_OUTPUT_FLAG_INCALL_MUSIC`, PCM16 8k/16k/48k **stereo only**, and the mixer_paths.xml
switches (`Incall_Music Audio Mixer MultiMedia9` / `..._2`). The blocker is entirely on the app
side. Three approaches were researched (see the exhaustive doc comment on
`IncallMusicInjector` for citations); summary:

| Option | Verdict | Why |
|---|---|---|
| (a) NDK dlopen of `libaudioclient.so`, hand-build native `android::AudioTrack` with the flag | Abandoned beyond a probe | Not in the app-visible linker namespace (needs a namespace-bypass `dlopen`, itself unreliable); and even loaded, the current constructor (AOSP `main`, `frameworks/av/media/libaudioclient/include/media/AudioTrack.h`) takes an `AttributionSourceState` (AIDL Parcelable) by value plus depends on libbinder/libutils/libaudiofoundation ABI that is explicitly unstable and unexported to apps. Hand-guessing that layout risks corrupting the audio-adjacent process, not just failing cleanly. |
| (b) Java `AudioAttributes`/`AudioTrack` hidden flags (+ `HiddenApiBypass`/`VMRuntime.setHiddenApiExemptions`) | Not viable | Checked every hidden `AudioAttributes.FLAG_*` in AOSP android-16 — none maps to `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`. That flag is an `audio_output_flags_t` (native/HAL concept); no Java surface, hidden or public, takes one. A hidden-API bypass unlocks more *methods* to call, but there's no method here that accepts this parameter in the first place. |
| (c) `AudioSystem` hidden reflection to open an output with flags | Not viable | Same root blocker as (b) — the flag isn't reachable from the Java-visible surface at all, hidden or not. |

**What `audioclient_probe.cpp` actually does:** a safe, read-only diagnostic. It tries a plain
`dlopen("libaudioclient.so")`, and if that fails (expected), tries the documented
namespace-bypass technique (`dlsym(RTLD_DEFAULT, "android_create_namespace")` +
`android_dlopen_ext` with `ANDROID_NAMESPACE_TYPE_SHARED|ISOLATED`, permitted path
`/system/lib64:/system/lib64/vndk` — see the file's header comment for the source). It reports
the result as a string and always `dlclose()`s on success; it never attempts to call anything
inside the library. `IncallMusicInjector.probe()` reports Route A as unavailable regardless of
this probe's outcome, per the ABI-risk reasoning above.

**What to look at in logcat:** tag `IncallMusicInjector` (Kotlin side, always logs the probe
detail as a warning) and `CallBridgeRouteA` (native side, logs the same string plus intermediate
dlopen/dlsym attempts at info level).

**Expected failure mode:** always unavailable. If a future milestone gets to pull this exact
build's `libaudioclient.so` off the device for offline symbol/ABI analysis (e.g. via `adb pull
/system/lib64/libaudioclient.so`, then `nm`/`c++filt` it against a matching AOSP source
checkout), that's the prerequisite before attempting (a) for real — not guessable from this
worktree alone.

**On-device test procedure:** run the probe (`Config.injectorRoute = INCALL_MUSIC`, tap "test
injection"), confirm it doesn't crash and logs a diagnostic string either way. No audio is
expected on the far phone.

## Route C — `LoopbackInjector` (physical fallback)

**File:** `app/src/main/java/bd/callbridge/audio/LoopbackInjector.kt`

**Mechanism:** a plain media `AudioTrack` (usage `MEDIA`) to the phone's default speaker. Does
no call routing — relies entirely on the physical rig: phone on speakerphone inside a padded
box, a second phone/laptop's mic feeds the Gemini bridge. Always works; used only if both B and
A fail (in practice: if B fails, since A never succeeds per above).

**What to look at in logcat:** tag `LoopbackInjector`.

**Expected failure mode:** only `AudioTrack` construction itself failing (e.g. `getMinBufferSize`
returning an error), which would indicate a deeper device audio-HAL problem unrelated to call
routing.

**On-device test procedure:** set `Config.injectorRoute = LOOPBACK`, tap "test injection", and
listen for the tone out of the phone's own speaker (not the far phone) — this route intentionally
never reaches the call.

## Mixer control helper — `MixerControl`

**Files:** `app/src/main/java/bd/callbridge/audio/MixerControl.kt`, `AlsaControlNames.kt`
(inline in the same file), `app/src/main/cpp/alsa_mixer.cpp`.

No `tinymix` and no `su` on the pilot phone (docs/hal-recon.md), so `MixerControl.get`/`set`
talk to `/dev/snd/controlC0` directly via the kernel ALSA control-interface ioctls
(`SNDRV_CTL_IOCTL_ELEM_READ`/`_WRITE`), using `<sound/asound.h>` shipped in the NDK r27 sysroot
(no hand-transcribed struct layouts). Targets from docs/hal-recon.md:
`Incall_Music Audio Mixer MultiMedia9` (line 3591) and `Incall_Music_2 Audio Mixer MultiMedia9`
(line 3702).

**Expected failure mode (called out explicitly in the brief):** the app is priv-app but not
guaranteed to be in the `audio` group, so opening `/dev/snd/controlC0` can fail with `EACCES`.
`MixerControl.get`/`set` map that to `MixerResult.PermissionDenied` rather than throwing or
crashing. If this happens on-device, the fix is on the install side (add the app's UID to the
`audio` group, or a SELinux domain change) — out of scope for this worker (owns
`Injector`/`InjectorRoute`/`NativeBridge`/native code only, not the install script/Magisk
module).

**On-device test procedure:** during a live call with Route B or C already producing audio to
the far phone but silent, call `MixerControl.set("Incall_Music Audio Mixer MultiMedia9", 1)`
(and the `_2` variant) from the status screen's test-injection path, retest. Record whichever
combination works below. Note: this toggles the mixer switch but per the Route A analysis above
there is currently no app-level way to get the HAL to actually *open* the `incall_music_uplink`
use case in the first place — this switch alone is unlikely to change anything for Route B/C,
but costs nothing to try and is explicitly requested by spec §4.3 step 3.

## Working-state log (on-device M1b testing, 2026-09-12)

**Route B is PROVEN on device.** Live GSM call (Robi LTE, SM-G781B, LineageOS 23.2), debug build
installed via `adb install -r` over the priv-app copy (priv perms retained). Trigger used:

```
adb shell am broadcast -n bd.callbridge/.debug.DebugInjectReceiver -a bd.callbridge.DEBUG_INJECT --es route TELEPHONY_TX --ei seconds 6
adb shell am broadcast -n bd.callbridge/.debug.DebugInjectReceiver -a bd.callbridge.DEBUG_CAPTURE --ei seconds 5
```
(The `-n` component target is REQUIRED — an implicit `-a`-only broadcast is dropped by background
broadcast limits and produces no log at all.)

| Route | Result | Device/probe detail | Notes |
|---|---|---|---|
| B (Telephony Tx) | **WORKS** — 1 kHz tone heard on the far phone by the operator | `deviceFound=true preferredDeviceSet=true routedDeviceId=13`, `getRoutedDevice()` = `TYPE_TELEPHONY(18)` product `SM-G781B`, before, ~300 ms into, and at end of playback | No mixer toggling needed. `getRoutedDevice()` also reports TYPE_TELEPHONY outside a call, so that check alone is not proof — far-phone audibility is. |
| A (Incall music) | unavailable (by design) | | |
| C (Loopback) | not run (B works) | | |
| Mixer controls | not needed | | |
| Capture (`VoiceCallCapture`, VOICE_DOWNLINK) | returns audio | 8 kHz mono, 5 s, `nonzero=true`, first ~1 s rms≈1800 then near-digital-silence (rms<5) while caller was quiet | GSM downlink silence is genuinely ~0 — VAD noise floor will sit very low. End-to-end voice quality still to be confirmed in M3. |
