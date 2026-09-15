#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-build/r7-m10-device}"
PACKAGE="com.alothmany.wa"
ACTIVITY="com.alothmany.wa.ui.MainActivity"

fail() { echo "R7_M10_DEVICE_GATE_FAIL: $*" >&2; exit 1; }
command -v adb >/dev/null || fail "adb not found"
[[ -f "$APK" ]] || fail "APK not found: $APK"
mkdir -p "$OUT"

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[[ ${#DEVICES[@]} -eq 1 ]] || fail "exactly one authorized Android device is required; found ${#DEVICES[@]}"
SERIAL="${DEVICES[0]}"
ADB=(adb -s "$SERIAL")

"${ADB[@]}" shell getprop ro.build.version.release > "$OUT/android-version.txt"
"${ADB[@]}" shell getprop ro.build.version.sdk > "$OUT/android-sdk.txt"
"${ADB[@]}" shell getprop ro.product.manufacturer > "$OUT/manufacturer.txt"
"${ADB[@]}" shell getprop ro.product.model > "$OUT/model.txt"
"${ADB[@]}" install -r "$APK" | tee "$OUT/install.txt"
grep -q 'Success' "$OUT/install.txt" || fail "APK installation failed"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am force-stop "$PACKAGE"
"${ADB[@]}" shell am start -W -n "$PACKAGE/$ACTIVITY" | tee "$OUT/launch.txt"
grep -Eq 'Status: ok|Complete' "$OUT/launch.txt" || fail "main activity did not launch successfully"
sleep 3
"${ADB[@]}" shell dumpsys package "$PACKAGE" > "$OUT/package.txt"
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$OUT/meminfo-start.txt"
"${ADB[@]}" logcat -d -v threadtime > "$OUT/logcat-start.txt"
if grep -A20 -B5 -F 'FATAL EXCEPTION' "$OUT/logcat-start.txt" | grep -Fq "$PACKAGE"; then
  fail "fatal exception detected after launch"
fi
cat > "$OUT/DEVICE_SMOKE_STATUS.txt" <<STATUS
R7-M10_DEVICE_SMOKE_GATE=PASS
serial=$SERIAL
package=$PACKAGE
apk=$(basename "$APK")
whatsapp_field_certification=PENDING
STATUS

echo "R7_M10_DEVICE_SMOKE_GATE_PASS"
