#!/usr/bin/env bash
# Builds the debug APK and installs it as a priv-app (spec §5).
#
# Default path (this pilot's phone, SM-G781B / LineageOS 23.2, verified via adb — see
# docs/hal-recon.md): no Magisk, no `su` binary. Root is LineageOS's "Rooted debugging" toggle,
# which lets `adb root` run as uid 0, and `adb remount` mounts /system (etc.) read-write via
# overlayfs. So install = adb root -> adb remount -> adb push the APK + permissions XML straight
# into /system -> reboot.
#
# Pass --magisk to use the magisk/ module skeleton instead (build -> copy into magisk/ ->
# zip -> adb push -> `magisk --install-module`), for a Magisk-rooted device.
#
# Not verified end-to-end against a real device from this script (shell-checked only:
# `bash -n scripts/install-privapp.sh`). Review each step before running.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_SRC="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PRIVAPP_XML="$ROOT_DIR/magisk/system/etc/permissions/privapp-permissions-callbridge.xml"

MODE="adb"
if [[ "${1:-}" == "--magisk" ]]; then
  MODE="magisk"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 1
fi

echo "==> Building debug APK"
"$ROOT_DIR/gradlew" -p "$ROOT_DIR" assembleDebug

if [[ ! -f "$APK_SRC" ]]; then
  echo "error: expected APK not found at $APK_SRC" >&2
  exit 1
fi

install_via_adb_remount() {
  echo "==> Waiting for a device"
  adb wait-for-device

  echo "==> adb root"
  adb root
  adb wait-for-device

  echo "==> adb remount (rw overlay on /system)"
  adb remount

  echo "==> Pushing APK to /system/priv-app/CallBridge/"
  adb shell mkdir -p /system/priv-app/CallBridge
  adb push "$APK_SRC" /system/priv-app/CallBridge/CallBridge.apk
  adb shell chmod 644 /system/priv-app/CallBridge/CallBridge.apk
  adb shell chown root:root /system/priv-app/CallBridge/CallBridge.apk

  echo "==> Pushing privapp-permissions XML to /system/etc/permissions/"
  adb push "$PRIVAPP_XML" /system/etc/permissions/privapp-permissions-callbridge.xml
  adb shell chmod 644 /system/etc/permissions/privapp-permissions-callbridge.xml
  adb shell chown root:root /system/etc/permissions/privapp-permissions-callbridge.xml

  echo "==> Rebooting so the priv-app install takes effect"
  adb reboot
}

install_via_magisk() {
  local module_dir="$ROOT_DIR/magisk"
  local apk_dest="$module_dir/system/priv-app/CallBridge/CallBridge.apk"
  local module_zip="$ROOT_DIR/build/callbridge-magisk-module.zip"
  local device_tmp_zip="/data/local/tmp/callbridge-magisk-module.zip"

  echo "==> Copying APK into Magisk module"
  cp "$APK_SRC" "$apk_dest"

  echo "==> Zipping Magisk module"
  mkdir -p "$(dirname "$module_zip")"
  rm -f "$module_zip"
  (cd "$module_dir" && zip -r -q "$module_zip" .)

  echo "==> Waiting for a device"
  adb wait-for-device

  echo "==> Pushing module zip to device"
  adb push "$module_zip" "$device_tmp_zip"

  echo "==> Installing via Magisk (requires root)"
  adb shell su -c "magisk --install-module $device_tmp_zip"

  echo "==> Done. Reboot the device for the priv-app install to take effect:"
  echo "    adb reboot"
}

if [[ "$MODE" == "magisk" ]]; then
  install_via_magisk
else
  install_via_adb_remount
fi
