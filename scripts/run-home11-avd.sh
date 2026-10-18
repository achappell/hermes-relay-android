#!/usr/bin/env bash
# Boots one owned, empty AVD, installs the debug and androidTest APKs, runs the
# ANDROID-HOME-11 layout matrix on it (scripts/run-home11-layout-matrix.sh) and
# shuts that emulator down. Run AVDs one at a time to avoid host contention.
#
# usage: run-home11-avd.sh <avd-name> <port> <row> <out-dir> [real|split|all]
set -uo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
ADB="$ANDROID_HOME/platform-tools/adb"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
AVD="$1"
PORT="$2"
ROW="$3"
OUT="$4"
MODE="${5:-all}"
SERIAL="emulator-$PORT"
mkdir -p "$OUT"

"$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" -no-audio -no-snapshot -no-window \
  -gpu swiftshader -no-boot-anim > "$OUT/emulator-$ROW.log" 2>&1 &
EMU_PID=$!
"$ADB" -s "$SERIAL" wait-for-device
until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done

{
  echo "## avd $AVD"
  "$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name
  "$ADB" -s "$SERIAL" shell getprop ro.build.fingerprint
  "$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk
  "$ADB" -s "$SERIAL" shell wm size
  "$ADB" -s "$SERIAL" shell wm density
} > "$OUT/native-profile-$ROW.txt"

"$ADB" -s "$SERIAL" install -r -t "$REPO/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s "$SERIAL" install -r -t "$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
# One ordinary launch that waits for the first frame, so the launcher has
# settled before the first test starts its Activity.
"$ADB" -s "$SERIAL" shell am start -W -n com.achappell.hermesrelay/.MainActivity
"$ADB" -s "$SERIAL" shell am force-stop com.achappell.hermesrelay

"$HERE/run-home11-layout-matrix.sh" "$SERIAL" "$ROW" "$OUT" "$MODE"

"$ADB" -s "$SERIAL" emu kill
wait "$EMU_PID" 2>/dev/null
echo "## $ROW complete -> $OUT"
