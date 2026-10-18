#!/usr/bin/env bash
# ANDROID-HOME-11 real-window layout matrix driver for one booted, owned AVD.
#
# For every cell (navigation mode x font scale x rotation x test class) it
# applies the device configuration, reads it back and fails the cell if the
# device does not report what was asked, writes the cell's receipt *after*
# applying it, then runs the instrumentation with the declared row as
# arguments; the tests assert the running window matches that declaration.
#
# Every adb call names its serial. Never point this at an emulator you do not own.
#
# usage: run-home11-layout-matrix.sh <serial> <row> <out-dir> [real|split|all]
#   <row> is one of the manifest rows below.
set -uo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.achappell.hermesrelay
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
SERIAL="$1"
ROW="$2"
OUT="$3"
MODE="${4:-all}"
mkdir -p "$OUT"

# Row manifest: natural display (WxH px) and the cells where the four-line
# status block is claimed (navigation-independent "<font>@<rotation>" pairs).
case "$ROW" in
  small-phone) DISPLAY_SIZE=720x1280;  STATUS_FOUR_LINES="1.3@0 2.0@0" ;;
  large-phone) DISPLAY_SIZE=1080x2400; STATUS_FOUR_LINES="" ;;
  tablet-10in) DISPLAY_SIZE=800x1280;  STATUS_FOUR_LINES="" ;;
  fold-inner)  DISPLAY_SIZE=1840x2208; STATUS_FOUR_LINES="" ;;
  *) echo "unknown row $ROW" >&2; exit 2 ;;
esac
# The full matrix by default; HOME11_* narrow it for a smoke run only.
SCALES="${HOME11_SCALES:-1.0 1.3 2.0}"
ROTATIONS="${HOME11_ROTATIONS:-0 1}"
NAVS="${HOME11_NAVS:-gestural threebutton}"
case "$MODE" in
  real)  CLASSES="DoorwayRealWindowMatrixTest" ;;
  split) CLASSES="DoorwaySplitWindowMatrixTest" ;;
  all)   CLASSES="DoorwayRealWindowMatrixTest DoorwaySplitWindowMatrixTest" ;;
  *) echo "unknown mode $MODE" >&2; exit 2 ;;
esac

adb_s() { "$ADB" -s "$SERIAL" "$@"; }
get() { adb_s shell "$@" | tr -d '\r'; }

nav_value() { [ "$1" = gestural ] && echo 2 || echo 0; }

# Applies and verifies one configuration; prints the reason and returns 1 on mismatch.
apply_cell() {
  local nav="$1" scale="$2" rot="$3" log="$4"
  {
    echo "## overlay"
    adb_s shell cmd overlay enable-exclusive --category "com.android.internal.systemui.navbar.$nav" 2>&1
    adb_s shell settings put system font_scale "$scale" 2>&1
    adb_s shell settings put secure show_ime_with_hard_keyboard 1 2>&1
    adb_s shell settings put system accelerometer_rotation 0 2>&1
    adb_s shell settings put system user_rotation "$rot" 2>&1
    adb_s shell wm user-rotation lock "$rot" 2>&1
    # A portrait-locked launcher keeps a phone at ROTATION_0 otherwise.
    adb_s shell wm fixed-to-user-rotation enabled 2>&1
  } >> "$log"
  local want_nav; want_nav="$(nav_value "$nav")"
  for _ in $(seq 1 30); do
    local got_scale got_nav got_rot
    got_scale="$(get settings get system font_scale)"
    got_nav="$(get settings get secure navigation_mode)"
    got_rot="$(get dumpsys window displays | grep -m1 -oE 'mRotation=ROTATION_[0-9]+' | grep -oE '[0-9]+$')"
    if [ "$got_scale" = "$scale" ] && [ "$got_nav" = "$want_nav" ] && [ "$((got_rot / 90))" = "$rot" ]; then
      return 0
    fi
    sleep 1
  done
  echo "CONFIG-MISMATCH want nav=$want_nav scale=$scale rot=$rot got nav=$got_nav scale=$got_scale rotation=$got_rot" | tee -a "$log"
  return 1
}

receipt() {
  local file="$1"
  {
    echo "## wm size";            adb_s shell wm size
    echo "## wm density";         adb_s shell wm density
    echo "## font_scale";         adb_s shell settings get system font_scale
    echo "## navigation_mode";    adb_s shell settings get secure navigation_mode
    echo "## navbar overlays";    adb_s shell cmd overlay list | grep -E 'navbar\.(gestural|threebutton)$'
    echo "## user_rotation";      adb_s shell settings get system user_rotation
    echo "## display";            adb_s shell dumpsys window displays | grep -m3 -E 'cur=|mRotation='
  } >> "$file"
}

run_cell() {
  local nav="$1" scale="$2" rot="$3" class="$4"
  local tag="$nav-scale$scale-rot$rot"
  local log="$OUT/$class-$tag.txt"
  local receipts="$OUT/receipts-$tag-$class.txt"
  : > "$receipts"
  if ! apply_cell "$nav" "$scale" "$rot" "$receipts"; then
    echo "### $class $tag CONFIG-MISMATCH (not run, not counted)" >> "$OUT/results-$ROW.txt"
    return 1
  fi
  receipt "$receipts"
  local status_arg=()
  case " $STATUS_FOUR_LINES " in *" $scale@$rot "*) status_arg=(-e home11_status_lines_min 4) ;; esac

  adb_s logcat -c
  adb_s exec-out screencap -p > "$OUT/$class-$tag-start.png"
  local instrumentation_status=0
  adb_s shell am instrument -w \
    -e class "$PKG.$class" \
    -e home11_row "$ROW" -e home11_font "$scale" -e home11_rotation "$rot" \
    -e home11_nav "$nav" -e home11_display "$DISPLAY_SIZE" ${status_arg[@]+"${status_arg[@]}"} \
    "$RUNNER" > "$log" 2>&1 || instrumentation_status=$?
  adb_s exec-out screencap -p > "$OUT/$class-$tag-end.png"
  adb_s logcat -d -s System.out:* > "$OUT/$class-$tag-logcat.txt" 2>/dev/null
  if [ "$class" = DoorwaySplitWindowMatrixTest ]; then
    adb_s pull "/sdcard/Android/data/$PKG/files/split-ime-up.png" "$OUT/$class-$tag-split-ime-up.png" >/dev/null 2>&1
  fi
  {
    echo "### $class $tag"
    grep -hE 'HOME11-(ROW|WINDOW|SPLIT)' "$OUT/$class-$tag-logcat.txt" | sed -E 's/.*(HOME11-)/\1/' || true
    grep -E 'OK \(|Tests run|FAILURES|^[0-9]+\) ' "$log" || true
  } >> "$OUT/results-$ROW.txt"
  if (( instrumentation_status != 0 )) || ! grep -Eq '^OK \([0-9]+ tests?\)$' "$log"; then
    echo "### $class $tag FAIL (exit=$instrumentation_status)" >> "$OUT/results-$ROW.txt"
    return 1
  fi
}

{
  echo "## row $ROW serial=$SERIAL mode=$MODE"
  echo "## avd $(get getprop ro.boot.qemu.avd_name)"
  echo "## fingerprint $(get getprop ro.build.fingerprint)"
} > "$OUT/device-$ROW.txt"

for nav in $NAVS; do
  for scale in $SCALES; do
    for rot in $ROTATIONS; do
      for class in $CLASSES; do
        if ! run_cell "$nav" "$scale" "$rot" "$class"; then
          echo "matrix stopped after first failed cell: $class $nav $scale rotation=$rot" >&2
          exit 1
        fi
      done
    done
  done
done

adb_s shell wm fixed-to-user-rotation default >/dev/null
adb_s shell wm user-rotation free >/dev/null
adb_s shell settings put system accelerometer_rotation 1
adb_s shell settings put system user_rotation 0
adb_s shell settings put system font_scale 1.0
adb_s shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
echo "matrix complete: $ROW -> $OUT"
