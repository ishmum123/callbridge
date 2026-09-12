# CallBridge

Rooted Android → Gemini Live PSTN gateway. Pilot for a rural AI helpline in Bangladesh.

Start with [`callbridge-spec.md`](./callbridge-spec.md) — full architecture, components, priv-app install, and milestone build order (M0 → M4). M1b (audio injection into the call uplink) is the gating problem; everything else is standard Android plumbing.

See [`docs/STATUS.md`](./docs/STATUS.md) for current milestone status and handoff notes for the audio-pipeline, injector/NDK, and Gemini-session workers, and [`docs/hal-recon.md`](./docs/hal-recon.md) for the pilot phone's real audio-HAL layout (device is LineageOS 23.2 / Android 16, not the One UI 13 the spec assumed).

## Build

Toolchain: Kotlin 2.0.21, AGP 8.13.0, Gradle 8.14.3 (via the committed wrapper), JDK 17+ (tested with JDK 23), NDK 27.3.13750724 for the native injection shim. Requires `ANDROID_HOME` set, or an `sdk.dir` line in a local (uncommitted) `local.properties`.

```sh
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # unit tests, incl. CallStateMachineTest
./gradlew lint            # Android lint
```

To use the Gemini API key for local testing, add it to `local.properties` (never commit this file):

```
GEMINI_API_KEY=your-key-here
```

## Priv-app install

The pilot phone (SM-G781B, LineageOS 23.2) has no Magisk — root is LineageOS's "Rooted debugging" (`adb root`) plus `adb remount` for a writable `/system` overlay (see `docs/hal-recon.md`). `scripts/install-privapp.sh` builds the debug APK, then `adb root && adb remount`s and pushes the APK plus the privapp-permissions XML straight to `/system/priv-app` / `/system/etc/permissions`, then reboots. The `magisk/` module directory is kept as an optional alternative for a Magisk-rooted device, but is not the default path. The script is shell-checked (`bash -n`) but not verified end-to-end against the phone — read it before running.

```sh
./scripts/install-privapp.sh
```
