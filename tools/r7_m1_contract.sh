#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "R7 M1 CONTRACT FAIL: $*" >&2; exit 1; }

if grep -Rqs 'android.permission.INTERNET' app/src/main/AndroidManifest.xml; then fail "INTERNET permission introduced"; fi
if grep -Rqs '/data/data/com.whatsapp' app/src/main/java; then fail "private WhatsApp DB path referenced"; fi
if grep -Rqs 'msgstore\.db' app/src/main/java; then fail "WhatsApp private DB referenced"; fi

for file in \
  app/src/main/java/com/alothmany/wa/r7/snapshot/NodeSnapshot.kt \
  app/src/main/java/com/alothmany/wa/r7/snapshot/ActionTargetEvidence.kt \
  app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityGateway.kt \
  app/src/main/java/com/alothmany/wa/r7/accessibility/LiveNodeResolutionPolicy.kt \
  app/src/main/java/com/alothmany/wa/r7/observe/ScreenStabilityDetector.kt \
  app/src/main/java/com/alothmany/wa/r7/adapter/WhatsAppAdapter.kt \
  app/src/main/java/com/alothmany/wa/r7/automation/ActionContracts.kt \
  app/src/main/java/com/alothmany/wa/r7/automation/CircuitBreaker.kt \
  app/src/main/java/com/alothmany/wa/r7/diagnostics/TraceModels.kt; do
  [[ -f "$file" ]] || fail "missing $file"
done

for token in btnSync btnDeep btnNew btnScanInvites btnJoinInvites btnPublish btnPause btnResume btnStop btnRecover btnExport; do
  grep -Rqs "$token" app/src/main || fail "UI compatibility token missing: $token"
done
for token in ACTION_SYNC ACTION_START_DEEP ACTION_START_NEW ACTION_PAUSE ACTION_RESUME ACTION_STOP ACTION_RECOVER ACTION_RUN_ACTION_JOB; do
  grep -Rqs "$token" app/src/main/java || fail "command compatibility token missing: $token"
done

grep -Rqs 'com.whatsapp' app/src/main/AndroidManifest.xml || fail "WhatsApp query missing"
grep -Rqs 'com.whatsapp.w4b' app/src/main/AndroidManifest.xml || fail "WhatsApp Business query missing"
echo "R7_M1_COMPAT_PASS"
