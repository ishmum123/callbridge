# CallBridge — session handoff (2026-09-12)

Authoritative running state. Read this + `docs/STATUS.md` + `docs/injection-routes.md` + `docs/gemini-live.md` + `docs/hal-recon.md` + `docs/device-log.md` before continuing. Spec is `callbridge-spec.md`.

## Where we are

Milestones M0, M1a, M1b (code), M2 (code) are built. Injection (the project's one hard problem) has a promising verified-feasible path but is **not yet proven on device** — that is the single most important open task.

| Milestone | State |
|---|---|
| M0 dialer | **Done, verified on the phone.** Priv-app installs, all 5 priv perms granted, default dialer set, inbound call auto-answered and held ACTIVE. Demo flag `Config.ANSWER_UNREGISTERED_CALLERS=true` answers every caller (flip false for the shop pilot to restore reject→callback). |
| M1a capture | Code-complete + Opus-reviewed + fixes applied, merged to main. Untested on device (needs a real call to confirm VOICE_DOWNLINK returns clean caller audio). |
| M1b injection | Code on branch `injector-ndk`; Opus review done; **fix worker was mid-flight when the session closed** (see below). Routes B/C built; Route A (INCALL_MUSIC) confirmed impossible from an app; **Route A2 (call-redirection) is the new primary path** — see Injection. |
| M2 Gemini bridge | Code on branch `gemini-session`; live handshake against real API succeeded; Opus review done; **fix worker was mid-flight when the session closed** (see below). |
| M3 wire-up | Not started. Compose capture → Gemini → injector into the active call; barge-in, greeting clip, watchdog, transcripts, status wiring. |
| M4 shop pilot | Not started. |

## Git / branch state

- `main` @ `e7edd17`, **pushed to origin** (github.com/ishmum123/callbridge, public). Contains M0 + M1a (merged) + callback fix + demo flag.
- Worktrees under `.worktrees/`: `audio-pipeline` (merged, can be removed), `gemini-session`, `injector-ndk`.
- **Two fix branches were being updated when the session closed.** Check `git -C .worktrees/gemini-session log --oneline` and `git -C .worktrees/injector-ndk log --oneline` for a WIP commit; if none, the fixes are uncommitted working-tree changes in those worktrees (`git -C .worktrees/<w> status`). Inspect, finish, or re-run the fix brief.
- Standing grant recorded: **always push `origin main` on a green state** (no per-push ask) for this repo.

### Merge order for next session
1. Finish/verify the two fix branches build green (`./gradlew testDebugUnitTest assembleDebug lint` in each worktree).
2. Rebase each onto `main` (both branched from the pre-callback-fix commit; expect a `docs/STATUS.md` text conflict only — resolve by keeping both milestone rows).
3. Merge `gemini-session` then `injector-ndk` into main, push.
4. Then M3 wiring.

## Injection — the make-or-break path (read `docs/injection-routes.md`)

Route priority is now: **A2 (call-redirection) → B (telephony-tx preferred device) → A (incall-music, unavailable) → C (physical loopback fallback)**.

**Route A2 (new, promising).** AOSP routes an AudioTrack flagged `AudioAttributes.FLAG_CALL_REDIRECTION` to the call's TX path if the app holds `CALL_AUDIO_INTERCEPTION`. **Verified on this phone: that permission is `signature|privileged|role`, so the `privileged` component lets our priv-app hold it via the allowlist** (no signature/role needed). The fix worker was adding the permission to `magisk/system/etc/permissions/privapp-permissions-callbridge.xml` + manifest and implementing `CallRedirectionInjector` (sets the hidden flag via reflection/HiddenApiBypass). The flag `FLAG_CALL_REDIRECTION = 1<<16` is NOT settable via public `Builder.setFlags()`.

**On-device verification still required (nobody has run this yet):**
1. Reinstall with the new allowlist entry, reboot, confirm: `adb shell dumpsys package bd.callbridge | grep CALL_AUDIO_INTERCEPTION` shows `granted=true`.
2. During a live call, play the 1 kHz test tone via the CallRedirection route; **the real success signal is `getRoutedDevice()?.type == TYPE_TELEPHONY`** AND the tone being audible on the far phone. Do NOT trust `setPreferredDevice`/probe "success" — it reports true even on silent fallback to earpiece (fixed in the probe rewrite).
3. If A2 fails, try B, then fall back to C (padded box + USB dongle + second device).

## Device facts (phone: SM-G781B / r8q)

- **LineageOS 23.2, Android 16, SDK 36** (NOT One UI — spec assumed One UI 13). Root via LineageOS "Rooted debugging": `adb root` → uid 0; `adb remount` → /system overlayfs rw.
- **Reboot loses the /system overlay** (files survive in /cache but aren't remounted). After every reboot: `adb root && adb remount && adb shell stop && adb shell start`. The debug APK also installs cleanly as a normal app via `adb install -r` for iteration; priv-app placement is only needed for the signature|privileged perms.
- **Phone must be unlocked (RUNNING_UNLOCKED)** before any app launches — remove the lock screen on the pilot device for unattended running.
- SIM: Robi, LTE. HAL confirms `voice_tx` (flag-free) and `incall_music_uplink` mixPorts route to `Telephony Tx`; both incall-rec uplink/downlink capture paths exist.

## Toolchain (this Mac)

Android SDK 34–36, NDK 27.3.13750724, cmake 3.22, JDK 23, `adb`, Gradle 8.14.3 wrapper committed. `gradle` CLI is Homebrew 9.7.1 and CANNOT evaluate this project (AGP 8.13) — always use `./gradlew`. `local.properties` (gitignored) holds `sdk.dir` + `GEMINI_API_KEY`; each worktree needs its own copy.

## Gemini (read `docs/gemini-live.md`)

- Model `gemini-3.1-flash-live-preview` (fallback `gemini-2.5-flash-native-audio-preview-12-2025`), voice **Sulafat**, language **bn-IN** (user decisions — settled). Live handshake confirmed against the real API with the provided key.
- **Server sends BINARY WebSocket frames** — a text-only onMessage silently receives nothing. Keep both overloads.
- Fix worker was addressing 3 blockers: (1) `[HANGUP]` bracket token can't survive an audio-only speech round-trip → replaced with a spoken Bangla closing phrase matched in the transcript; (2) watchdog was firing on caller silence → re-armed only while a model reply is outstanding; (3) events emitted before subscription were dropped. Plus socket-lifecycle (onClosing/onFailure/goAway), send backpressure, leak fixes, prompt tweaks.

## API key exposure

`GEMINI_API_KEY` was pasted in chat and is baked into the debug APK via BuildConfig — decompilable. Rotate it in AI Studio after the hackathon.

## Immediate next steps (in order)

1. Recover the two in-flight fix branches (WIP commit or working-tree changes in the worktrees), finish them, get each green.
2. Rebase + merge `gemini-session` and `injector-ndk` into main; push.
3. **Verify Route A2 on device** — the milestone-deciding test (grant check → live tone → far-phone audible + routedToTelephony). This is the top priority; the whole product depends on it.
4. M3: wire capture → Gemini → injector into the active call; run the first end-to-end Bangla conversation; measure round-trip (<1.5 s target).
