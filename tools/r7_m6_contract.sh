#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$ROOT/app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt"
CONTROLLER="$ROOT/app/src/main/java/com/alothmany/wa/automation/AutomationController.kt"
SERVICE="$ROOT/app/src/main/java/com/alothmany/wa/service/AutomationForegroundService.kt"
RUNTIME="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/AutomationRuntime.kt"
BRIDGE="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt"
ACTION="$ROOT/app/src/main/java/com/alothmany/wa/automation/ActionAutomationEngine.kt"

for f in \
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/navigation/RecoveryController.kt" \
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/Watchdog.kt" \
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/RunLifecycle.kt" \
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityEventBuffer.kt" \
  "$ROOT/tools/run_r7_m6_tests.sh"; do
  test -f "$f" || { echo "R7_M6_COMPAT_FAIL missing $f"; exit 1; }
done

grep -q 'RecoveryController' "$ENGINE"
grep -q 'checkWatchdog' "$ENGINE"
grep -q 'WATCHDOG_TRIGGERED' "$ENGINE"
grep -q 'PAUSED_AFTER_DURABLE_BATCH' "$ENGINE"
grep -q 'RESUMED_FROM_DURABLE_CHECKPOINT' "$ENGINE"
grep -q 'SMART_SCROLL_RECOVERED' "$ENGINE"
grep -q 'FAIL_ITEM_CONTINUE' "$ROOT/app/src/main/java/com/alothmany/wa/r7/navigation/RecoveryController.kt"
grep -q 'SERVICE_RECREATED' "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/Watchdog.kt"
grep -q 'GESTURE_TIMEOUT' "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/Watchdog.kt"
grep -q 'PAUSE_REQUESTED' "$CONTROLLER"
grep -q 'currentGeneration' "$CONTROLLER"
grep -q 'acceptsCallback' "$CONTROLLER"
grep -q 'eventBuffer' "$RUNTIME"
grep -q 'currentServiceGeneration' "$RUNTIME"
grep -q 'activeGestureStartedAt' "$RUNTIME"
grep -q 'dispatchTrackedGesture' "$BRIDGE"
grep -q 'awaitActionBoundary' "$ACTION"
grep -q 'ACTION_PAUSED_ATOMIC_BOUNDARY' "$ACTION"

# Service must not claim durable PAUSED immediately on button press.
python3 - "$SERVICE" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
start=s.index('private fun pauseAutomation()')
end=s.index('private fun resumeAutomation()', start)
block=s[start:end]
if 'updateExtractionStatus(id, "PAUSED")' in block or 'updateActionStatus(id, "PAUSED")' in block:
    raise SystemExit('R7_M6_COMPAT_FAIL service persists PAUSED before atomic boundary')
if 'Pause requested' not in block:
    raise SystemExit('R7_M6_COMPAT_FAIL pause request semantics missing')
print('R7_M6_PAUSE_ORDER_PASS')
PY

# Stop must route through controllers, whose lifecycle increments generation.
grep -q 'extractionEngine?.controller?.stop()' "$SERVICE"
grep -q 'actionEngine?.controller?.stop()' "$SERVICE"
grep -q 'generation++' "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/RunLifecycle.kt"

# Gesture watchdog must observe the actual tracked gesture timestamp.
grep -q 'gestureStartedAt = AutomationRuntime.activeGestureStartedAt()' "$ENGINE"

if grep -Rqs 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo 'R7_M6_COMPAT_FAIL INTERNET permission present'
  exit 1
fi

echo 'R7_M6_COMPAT_PASS'
