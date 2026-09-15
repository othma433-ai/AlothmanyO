#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "R7 M3 CONTRACT FAIL: $*" >&2; exit 1; }

./tools/r7_m2_contract.sh >/dev/null

for file in \
  app/src/main/java/com/alothmany/wa/r7/extraction/ConversationVerifier.kt \
  app/src/main/java/com/alothmany/wa/r7/navigation/ConversationNavigator.kt \
  app/src/main/java/com/alothmany/wa/r7/navigation/SmartScrollController.kt \
  tools/run_r7_m3_tests.sh \
  tools/r7m3test/R7NavigationTests.kt; do
  [[ -f "$file" ]] || fail "missing $file"
done

ENGINE=app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt
BRIDGE=app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt
HOST=app/src/main/java/com/alothmany/wa/accessibility/AndroidAccessibilityServiceHost.kt
SHADOW=app/src/main/java/com/alothmany/wa/r7/diagnostics/R7ShadowRuntime.kt
VERIFIER=app/src/main/java/com/alothmany/wa/r7/extraction/ConversationVerifier.kt
NAV=app/src/main/java/com/alothmany/wa/r7/navigation/ConversationNavigator.kt
SCROLL=app/src/main/java/com/alothmany/wa/r7/navigation/SmartScrollController.kt

for token in \
  'ConversationVerifier()' \
  'ConversationNavigator(maxWeakReopens = 2)' \
  'openVerifiedConversation' \
  'VerifiedConversation' \
  'SmartScrollController(requiredTerminalConfirmations = 3)' \
  'SemanticScrollDirection.OLDER' \
  'semanticScroll'; do
  grep -Fqs "$token" "$ENGINE" || fail "runtime integration token missing: $token"
done

for token in \
  'semanticScroll' \
  'dispatchSemanticNodeScroll' \
  'dispatchSemanticGesture' \
  'scrollTelemetrySnapshot' \
  'chatTitle?.trim()?.replace'; do
  grep -Fqs "$token" "$BRIDGE" || fail "bridge M3 invariant missing: $token"
done

for token in \
  'ConversationVerificationStatus.HARD_REJECT' \
  'ConversationVerificationStatus.WEAK_MISMATCH' \
  'observedTitle != expectedTitle' \
  'VerifiedConversation'; do
  grep -Fqs "$token" "$VERIFIER" || fail "conversation verifier invariant missing: $token"
done

for token in \
  'NavigationAction.REOPEN' \
  'NavigationAction.RECOVER' \
  'NavigationAction.REJECT'; do
  grep -Fqs "$token" "$NAV" || fail "navigation policy invariant missing: $token"
done

for token in \
  'ScrollStrategy.PRIMARY_NODE' \
  'ScrollStrategy.ALTERNATE_NODE' \
  'ScrollStrategy.SAFE_GESTURE' \
  'ScrollDirectiveAction.BOUNDARY' \
  'telemetryAdvanced'; do
  grep -Fqs "$token" "$SCROLL" || fail "smart-scroll invariant missing: $token"
done

# Loose title matching and legacy extraction scrolling must no longer gate extraction.
if grep -Fqs 'snapshotMatchesGroup' "$ENGINE"; then fail "legacy loose snapshotMatchesGroup gate still present"; fi
if grep -Fqs 'bridge.scrollOlder()' "$ENGINE"; then fail "legacy scrollOlder extraction path still present"; fi
if grep -Fqs 'chatTitle?.contains(name, ignoreCase = true)' "$BRIDGE"; then fail "contains-only chat title verification still present"; fi

# Extraction must require a verifier-issued token before persistence can begin.
grep -Fqs 'private suspend fun extractGroup(' "$ENGINE" || fail "extractGroup function missing"
grep -Fqs 'verifiedConversation: VerifiedConversation' "$ENGINE" || fail "extractGroup does not require VerifiedConversation token"

# Scroll-specific telemetry must be wired from AccessibilityEvent.TYPE_VIEW_SCROLLED.
grep -Fqs 'R7ShadowRuntime.onScrollEvent' "$HOST" || fail "scroll event telemetry hook missing"
grep -Fqs 'scrollEventCount' "$SHADOW" || fail "scroll telemetry counter missing"

if grep -Rqs 'android.permission.INTERNET' app/src/main/AndroidManifest.xml; then fail "INTERNET permission introduced"; fi

echo "R7_M3_COMPAT_PASS"
