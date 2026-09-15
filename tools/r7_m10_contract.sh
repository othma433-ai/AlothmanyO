#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WF="$ROOT/.github/workflows/android-debug.yml"
APP="$ROOT/app/build.gradle.kts"
VERIFY="$ROOT/tools/verify_r7_m10_artifacts.sh"
DEVICE="$ROOT/tools/r7_m10_device_gate.sh"

fail(){ echo "R7_M10_CONTRACT_FAIL: $*" >&2; exit 1; }
[[ -f "$WF" && -f "$VERIFY" && -f "$DEVICE" ]] || fail "M10 gate files missing"
grep -Fq "java-version: '17'" "$WF" || fail "JDK 17 gate missing"
grep -Fq 'gradle-version: '\''8.11.1'\''' "$WF" || fail "Gradle 8.11.1 gate missing"
grep -Fq 'platforms;android-35' "$WF" || fail "Android SDK 35 gate missing"
grep -Fq 'build-tools;35.0.0' "$WF" || fail "Build Tools 35.0.0 gate missing"
for task in ':app:testDebugUnitTest' ':app:lintDebug' ':app:assembleDebug' ':app:assembleRelease'; do
  grep -Fq "$task" "$WF" || fail "missing build task $task"
done
grep -Fq './tools/verify_r7_m10_artifacts.sh' "$WF" || fail "artifact verification step missing"
grep -Fq 'app-release-unsigned.apk' "$WF" || fail "unsigned release artifact path missing"
if grep -Fq 'app/build/outputs/apk/release/app-release.apk' "$WF"; then
  fail "incorrect signed release path still present"
fi
grep -Fq 'WA-Al-Othmany-v2.2.0-R7-M10-debug' "$WF" || fail "M10 debug artifact label missing"
grep -Fq 'WA-Al-Othmany-v2.2.0-R7-M10-release-unsigned' "$WF" || fail "M10 unsigned release artifact label missing"
grep -Fq 'compileSdk = 35' "$APP" || fail "compileSdk mismatch"
grep -Fq 'targetSdk = 35' "$APP" || fail "targetSdk mismatch"
grep -Fq 'sourceCompatibility = JavaVersion.VERSION_17' "$APP" || fail "Java 17 source level missing"
for forbidden in 'storePassword' 'keyPassword' 'keyAlias' 'storeFile'; do
  if grep -Fq "$forbidden" "$APP"; then fail "unexpected embedded signing material: $forbidden"; fi
done
[[ -f "$ROOT/docs/R7_M10_CERTIFICATION.md" ]] || fail "certification doc missing"
[[ -f "$ROOT/docs/R7_M10_FIELD_RESULTS_TEMPLATE.csv" ]] || fail "field results template missing"
echo "R7_M10_CONTRACT_PASS"
