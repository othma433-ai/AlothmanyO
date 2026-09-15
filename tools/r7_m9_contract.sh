#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/WaAccessibilityService.kt"
HOST="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/AndroidAccessibilityServiceHost.kt"
COORD="$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityHostCoordinator.kt"
RUNTIME="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/AutomationRuntime.kt"
BRIDGE="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt"

for f in "$SERVICE" "$HOST" "$COORD" "$RUNTIME" "$BRIDGE" "$ROOT/tools/run_r7_m9_tests.sh"; do
  test -f "$f" || { echo "R7_M9_COMPAT_FAIL missing $f"; exit 1; }
done

for token in 'AccessibilityHostCoordinator' 'AccessibilityHostRuntimePort' 'AccessibilityHostTelemetryPort'; do
  grep -q "$token" "$COORD" || { echo "R7_M9_COMPAT_FAIL coordinator invariant missing $token"; exit 1; }
done

grep -q 'private val host = AndroidAccessibilityServiceHost()' "$SERVICE" || { echo 'R7_M9_COMPAT_FAIL service host delegation missing'; exit 1; }
for forbidden in 'AutomationRuntime' 'R7ShadowRuntime' 'WhatsAppUiBridge' 'AutomationEngine' 'ActionAutomationEngine' 'eventBuffer' 'rootInActiveWindow' 'dispatchGesture'; do
  if grep -q "$forbidden" "$SERVICE"; then
    echo "R7_M9_COMPAT_FAIL service contains runtime/workflow logic: $forbidden"
    exit 1
  fi
done
lines=$(wc -l < "$SERVICE")
if [ "$lines" -gt 40 ]; then
  echo "R7_M9_COMPAT_FAIL service is not thin: ${lines} lines"
  exit 1
fi

grep -q 'AccessibilityHostCoordinator' "$HOST" || { echo 'R7_M9_COMPAT_FAIL android host does not use coordinator'; exit 1; }
grep -q 'AutomationRuntime.eventBuffer.record' "$HOST" || { echo 'R7_M9_COMPAT_FAIL event buffer routing missing'; exit 1; }
grep -q 'R7ShadowRuntime.onAccessibilityEvent' "$HOST" || { echo 'R7_M9_COMPAT_FAIL event telemetry routing missing'; exit 1; }
grep -q 'R7ShadowRuntime.onScrollEvent' "$HOST" || { echo 'R7_M9_COMPAT_FAIL scroll telemetry routing missing'; exit 1; }

grep -q '@Volatile var accessibility: AccessibilityService?' "$RUNTIME" || { echo 'R7_M9_COMPAT_FAIL runtime still depends on concrete service'; exit 1; }
if grep -q 'WaAccessibilityService' "$RUNTIME"; then
  echo 'R7_M9_COMPAT_FAIL runtime concrete service coupling remains'
  exit 1
fi

grep -q 'private val service: AccessibilityService' "$BRIDGE" || { echo 'R7_M9_COMPAT_FAIL bridge does not depend on base AccessibilityService'; exit 1; }
if grep -q 'private val service: WaAccessibilityService' "$BRIDGE"; then
  echo 'R7_M9_COMPAT_FAIL bridge concrete service coupling remains'
  exit 1
fi
if grep -q 'service.lastEventAt' "$BRIDGE"; then
  echo 'R7_M9_COMPAT_FAIL bridge reads service-owned event state'
  exit 1
fi
grep -q 'AutomationRuntime.eventBuffer.lastEventAt()' "$BRIDGE" || { echo 'R7_M9_COMPAT_FAIL bridge does not use durable event telemetry boundary'; exit 1; }

if grep -Rqs 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo 'R7_M9_COMPAT_FAIL INTERNET permission present'
  exit 1
fi

echo 'R7_M9_COMPAT_PASS'
