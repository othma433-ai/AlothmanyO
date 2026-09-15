#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="$ROOT/app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfile.kt"
REPO="$ROOT/app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfileRepository.kt"
ASTORE="$ROOT/app/src/main/java/com/alothmany/wa/r7/profile/AndroidRuntimeProfileStore.kt"
CAPTURE="$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/DiagnosticCapture.kt"
REPLAY="$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/ReplayFixture.kt"
ARTIFACT="$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/DiagnosticArtifactStore.kt"
BRIDGE="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt"
ENGINE="$ROOT/app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt"
SERVICE="$ROOT/app/src/main/java/com/alothmany/wa/service/AutomationForegroundService.kt"
UI="$ROOT/app/src/main/java/com/alothmany/wa/ui/MainActivity.kt"

for f in "$PROFILE" "$REPO" "$ASTORE" "$CAPTURE" "$REPLAY" "$ARTIFACT" \
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/R7NodeSnapshotCollector.kt" \
  "$ROOT/tools/run_r7_m7_tests.sh"; do
  test -f "$f" || { echo "R7_M7_COMPAT_FAIL missing $f"; exit 1; }
done

for token in packageName appVersion localeTag androidVersion deviceClass instanceIdentity; do
  grep -q "val $token" "$PROFILE" || { echo "R7_M7_COMPAT_FAIL profile key missing $token"; exit 1; }
done
grep -q 'stored.appVersion != current.appVersion' "$PROFILE"
grep -q 'return 0.25' "$PROFILE"
grep -q 'if (!verified || value.isBlank()) return' "$REPO"
grep -q 'verifiedSuccessThreshold: Int = 3' "$REPO"
grep -q 'requiresVerification = true' "$REPO"
grep -q 'AndroidRuntimeProfileStore' "$BRIDGE"
grep -q 'recommendations(profileKey, ProfileHintType.GROUP_FILTER_VIEW_ID)' "$BRIDGE"
grep -q 'Profiles only prioritize a candidate' "$BRIDGE"
grep -q 'recordSuccess' "$BRIDGE"
grep -q 'recordFailure' "$BRIDGE"

grep -q 'DiagnosticCapture' "$BRIDGE"
grep -q 'ReplayFixture' "$BRIDGE"
grep -q 'DiagnosticArtifactStore' "$BRIDGE"
grep -q 'captureR7DiagnosticArtifact' "$ENGINE"
grep -q 'ACTION_CAPTURE_DIAGNOSTIC' "$SERVICE"
grep -q 'setOnLongClickListener' "$UI"
grep -q 'MessageDigest.getInstance("SHA-256")' "$CAPTURE"
grep -q 'ReplayEngine' "$REPLAY"
grep -q 'ScreenClassifier' "$REPLAY"
grep -q 'GroupGrabberController' "$REPLAY"
grep -q 'SmartScrollController' "$REPLAY"
grep -q 'TerminalConsensus' "$REPLAY"

# Diagnostic layer must consume already-sanitized NodeSnapshot and never raw Accessibility text.
if grep -Rqs 'AccessibilityNodeInfo' "$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics"; then
  echo 'R7_M7_COMPAT_FAIL diagnostics depend on live AccessibilityNodeInfo'
  exit 1
fi
if grep -REqs 'node\.text([^A-Za-z0-9_]|$)|contentDescription\?\.toString' "$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics"; then
  echo 'R7_M7_COMPAT_FAIL diagnostics read raw accessibility text'
  exit 1
fi
if grep -Rqs 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo 'R7_M7_COMPAT_FAIL INTERNET permission present'
  exit 1
fi

echo 'R7_M7_COMPAT_PASS'
