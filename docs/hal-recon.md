# HAL reconnaissance — SM-G781B (r8q), LineageOS 23.2

Pulled 2026-09-12 from the pilot phone via adb (no root needed; files are world-readable).
Raw files in `docs/hal/`. This answers spec §10 open question 2 and picks the injection route.

## Device
- Model SM-G781B, codename r8q, `ro.hardware=qcom`, Snapdragon 865 (kona)
- OS: **LineageOS 23.2 nightly 20260411, Android 16, SDK 36** (spec assumed One UI 13 — adjust everywhere)
- Root: **LineageOS Rooted debugging** (Developer options). `adb root` → uid 0. No Magisk, no `su` binary for apps.
- `adb remount` works: `/system`, `/system_ext`, `/product` become overlayfs rw (upper in `/cache/overlay`). The vbmeta error it prints is harmless. **Priv-app install = `adb root && adb remount && adb push`**, no Magisk module needed. Survives reboot as long as the overlay stays (re-run `adb remount` if it drops).
- No `tinymix` on the phone (`/proc/asound/cards`: kona-mtp-snd-card). Build tinyalsa via NDK or push a prebuilt arm64 binary when Route A needs mixer toggling.
- SIM: Robi, LTE, slot 1 loaded.

## Injection — Route A (in-call music uplink) is fully present
`audio_policy_configuration.xml`
- mixPort `incall_music_uplink`, flags `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`, PCM16, rates **8000/16000/48000**, channel mask **STEREO only**
- devicePort `Telephony Tx` type `AUDIO_DEVICE_OUT_TELEPHONY_TX`, PCM16, rates 8000/16000, mono or stereo
- route `sink="Telephony Tx" sources="voice_tx,incall_music_uplink"`

`audio_platform_info.xml`
- `USECASE_INCALL_MUSIC_UPLINK` (id 23) and `USECASE_INCALL_MUSIC_UPLINK2`

`mixer_paths.xml`
- `<path name="incall_music_uplink">` → `Incall_Music Audio Mixer MultiMedia9 = 1` (line 3591), plus per-device variants (handset, speaker, bt-sco, …)
- `incall_music_uplink2` → `Incall_Music_2 Audio Mixer MultiMedia9 = 1` (line 3702)
- defaults at line 320–322 all 0; the HAL flips them when the use case opens

**Implication for the NDK shim:** open the native AudioTrack with `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`, PCM16, **stereo**, 16 kHz first (both mixPort and Telephony Tx accept it), fall back to 8 kHz. Mono may be rejected by the mixPort profile — duplicate the channel. If the caller hears nothing, `tinymix` toggle `Incall_Music Audio Mixer MultiMedia9` to 1 during a call (needs root).

**Route B** (`AudioTrack.setPreferredDevice` → TELEPHONY_TX) also viable: the device port is exposed. Still try B first per spec — 30 min test.

## Capture — M1a
`mixer_paths.xml` has `incall-rec-uplink`, `incall-rec-downlink`, `incall-rec-uplink-and-downlink` paths, and `audio_platform_info.xml` declares `USECASE_INCALL_REC_{UPLINK,DOWNLINK,UPLINK_AND_DOWNLINK}`. So `AudioSource.VOICE_DOWNLINK` (caller only) should work directly, as should `VOICE_CALL` (mix). Spec §4.2 verification still needed by ear.

## Next on-device steps
1. ~~Root~~ done (Rooted debugging + adb remount).
2. M1a: install priv-app, capture a test call, listen.
3. M1b: Route B → Route A tone test; record working `tinymix` state here.
