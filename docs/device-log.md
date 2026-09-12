# Device log — SM-G781B (r8q), LineageOS 23.2

Phone facts: see [`docs/hal-recon.md`](./hal-recon.md) (Android 16 / SDK 36, `adb root` +
`adb remount` rooted-debugging, no Magisk/`su`, priv-app install via direct push).

## 2026-09-12 — callback-flow crash on first on-device inbound call

**Symptom:** app installed as priv-app + default dialer, first inbound call from an unregistered
number reached `CallBridgeInCallService`. Flow: ring → app rejects (correct) → Telecom fires
`onCallRemoved` → controller moves `ANSWERED/REJECTED_FOR_CALLBACK/ACTIVE → ENDED → IDLE` → ~2s
later the callback timer fires `CallStateMachine.onCallbackTimerFired()` → the old code's
`check(state == REJECTED_FOR_CALLBACK)` throws `IllegalStateException: onCallbackTimerFired called
in state IDLE` on `Dispatchers.Default` → uncaught exception on that coroutine's thread → process
dies (device shows "CallBridge keeps stopping"). Reproduced on every unknown-caller ring.

**Root cause (category, not just this instance):** `CallStateMachine` had no state for the window
between rejecting the inbound leg and the outbound callback connecting - it modeled the rejected
inbound call being removed by Telecom as the *same event* as the call actually ending, so removal
raced the pending callback timer straight to `IDLE`. On top of that, every transition method used
a throwing `check()`, so *any* out-of-order Telecom callback (not just this one) would crash the
process instead of being ignored. `CallController.placeCall` also silently no-op'd when
`activeTelecomCall` was null (true here, since the inbound call had already been removed), so even
without the crash the callback would never have been placed.

**Fix (this change, `app/src/main/java/bd/callbridge/call/`):**
1. Added `CallState.CALLING_BACK` and made `REJECTED_FOR_CALLBACK` explicitly survive the rejected
   inbound leg being removed while its timer is still pending (`onCallEnded` no-ops in that case
   instead of ending the machine).
2. Replaced every throwing `check()` in `CallStateMachine` with a non-throwing guard: an
   out-of-order event now returns `emptyList()` and records `lastIllegalTransition` (pure Kotlin,
   no logging inside the machine); `CallController` logs it via `Log.w` instead of crashing.
3. `CallController.scope` now carries a `CoroutineExceptionHandler` that logs instead of letting an
   uncaught exception kill the process (defense in depth on top of #2).
4. `scheduleCallback` keeps its `Job` so it can be cancelled (own hangUp, a fresh ring for the same
   number, or the InCallService being destroyed); `PlaceCallback` goes straight through
   `TelecomManager.placeCall` without needing a `Call` object, fixing the silent no-op.
5. The outbound call Telecom delivers via `onCallAdded` while `CALLING_BACK` is attached directly
   as our own leg (no caller lookup, no reject); a re-ring for the same/any number while waiting
   cancels the pending timer; unknown-caller reschedules are capped at 1 per number per 60s.

Full state diagram and test list: see the commit message / `CallStateMachineTest`.

**Operational notes for the next on-device session:**
- The `/system` overlay from `adb remount` is **not** remounted at boot. After every reboot, run
  `adb root && adb remount` again, then `adb shell stop && adb shell start` before relying on the
  priv-app permissions.
- The phone must be in `RUNNING_UNLOCKED` (lock screen dismissed) before any non-direct-boot app,
  ours included, can launch. Remove the lock screen entirely on the pilot device to avoid this
  gating every cold start/reboot.
