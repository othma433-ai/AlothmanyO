#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEBUG_APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
RELEASE_APK="${2:-$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk}"
OUT_DIR="${3:-$ROOT/build/r7-m10}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_TOOLS="${R7_BUILD_TOOLS:-35.0.0}"
AAPT2="${R7_AAPT2:-${SDK_ROOT:+$SDK_ROOT/build-tools/$BUILD_TOOLS/aapt2}}"
APKSIGNER="${R7_APKSIGNER:-${SDK_ROOT:+$SDK_ROOT/build-tools/$BUILD_TOOLS/apksigner}}"

fail() { echo "R7_M10_ARTIFACT_GATE_FAIL: $*" >&2; exit 1; }

[[ -f "$DEBUG_APK" ]] || fail "missing debug APK: $DEBUG_APK"
[[ -f "$RELEASE_APK" ]] || fail "missing unsigned release APK: $RELEASE_APK"
[[ -x "$AAPT2" ]] || fail "aapt2 not found/executable: ${AAPT2:-unset}"
[[ -x "$APKSIGNER" ]] || fail "apksigner not found/executable: ${APKSIGNER:-unset}"

mkdir -p "$OUT_DIR"
unzip -tq "$DEBUG_APK" >/dev/null || fail "debug APK zip integrity failed"
unzip -tq "$RELEASE_APK" >/dev/null || fail "release APK zip integrity failed"

DEBUG_BADGING="$($AAPT2 dump badging "$DEBUG_APK")"
RELEASE_BADGING="$($AAPT2 dump badging "$RELEASE_APK")"
for badging in "$DEBUG_BADGING" "$RELEASE_BADGING"; do
  grep -Fq "package: name='com.alothmany.wa'" <<<"$badging" || fail "applicationId mismatch"
  grep -Fq "versionCode='20200'" <<<"$badging" || fail "versionCode mismatch"
  grep -Fq "versionName='2.2.0'" <<<"$badging" || fail "versionName mismatch"
done

"$APKSIGNER" verify --verbose "$DEBUG_APK" > "$OUT_DIR/debug-signature.txt" || fail "debug APK is not verifiably signed"
if "$APKSIGNER" verify --verbose "$RELEASE_APK" > "$OUT_DIR/release-signature.txt" 2>&1; then
  fail "release artifact unexpectedly signed; M10 expects unsigned release until production signing is configured"
else
  echo "EXPECTED_UNSIGNED_RELEASE" > "$OUT_DIR/release-signature.txt"
fi

sha256sum "$DEBUG_APK" "$RELEASE_APK" > "$OUT_DIR/SHA256SUMS.txt"
printf '%s\n' "$DEBUG_BADGING" > "$OUT_DIR/debug-badging.txt"
printf '%s\n' "$RELEASE_BADGING" > "$OUT_DIR/release-badging.txt"

cat > "$OUT_DIR/BUILD_STATUS.txt" <<STATUS
R7-M10_ANDROID_BUILD_ARTIFACT_GATE=PASS
applicationId=com.alothmany.wa
versionCode=20200
versionName=2.2.0
debug_signature=VERIFIED
release_signature=EXPECTED_UNSIGNED
release_production_signing=PENDING
STATUS

echo "R7_M10_ARTIFACT_GATE_PASS"
