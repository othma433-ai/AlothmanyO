#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "R7 M2 CONTRACT FAIL: $*" >&2; exit 1; }

./tools/r7_m1_contract.sh >/dev/null

for file in \
  app/src/main/java/com/alothmany/wa/r7/sync/SyncModels.kt \
  app/src/main/java/com/alothmany/wa/r7/sync/TerminalConsensus.kt \
  app/src/main/java/com/alothmany/wa/r7/sync/SyncController.kt \
  app/src/main/java/com/alothmany/wa/r7/sync/GroupGrabberController.kt \
  tools/run_r7_m2_tests.sh \
  tools/r7m2test/R7SyncControllerTests.kt; do
  [[ -f "$file" ]] || fail "missing $file"
done

ENGINE=app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt
BRIDGE=app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt
CONSENSUS=app/src/main/java/com/alothmany/wa/r7/sync/TerminalConsensus.kt
SYNC=app/src/main/java/com/alothmany/wa/r7/sync/SyncController.kt
MODELS=app/src/main/java/com/alothmany/wa/r7/sync/SyncModels.kt
GRABBER=app/src/main/java/com/alothmany/wa/r7/sync/GroupGrabberController.kt

for token in \
  'syncViaGroupsFilter' \
  'syncViaChatGrabber' \
  'SyncController(requiredTerminalConfirmations = 3)' \
  'navigateToGroupsFilterVerified' \
  'SYNC_STRATEGY_SWITCH' \
  'SYNC_TERMINAL_CONSENSUS' \
  'CHAT_GRABBER_TERMINAL_CONSENSUS' \
  'classification = candidate.provenance.name'; do
  grep -Fqs "$token" "$ENGINE" || fail "runtime integration token missing: $token"
done

for token in \
  'navigateToGroupsFilterVerified' \
  'groupsFilterSelected' \
  'openVisibleChatByName' \
  'currentConversationProbeLabels' \
  'findGroupsFilterNode'; do
  grep -Fqs "$token" "$BRIDGE" || fail "verified bridge token missing: $token"
done

for token in \
  'requiredConfirmations' \
  'newStableKeys.isNotEmpty()' \
  'scrollProgressed || evidence.telemetryAdvanced' \
  'sameTerminalShape' \
  'TerminalStatus.TERMINAL'; do
  grep -Fqs "$token" "$CONSENSUS" || fail "terminal consensus invariant missing: $token"
done

grep -Fqs 'enum class DiscoveryProvenance { GROUPS_FILTER, CHAT_GRABBER }' "$MODELS" || fail "provenance enum missing"
for token in \
  'stableGroupKey' \
  'terminalConsensus.observe'; do
  grep -Fqs "$token" "$SYNC" "$MODELS" "$GRABBER" || fail "sync provenance/dedup invariant missing: $token"
done
grep -Fqs 'DiscoveryProvenance.CHAT_GRABBER' "$GRABBER" || fail "chat-grabber provenance assignment missing"

grep -Fqs 'one no-op must only start confirmation' tools/r7m2test/R7SyncControllerTests.kt || fail "single-no-op regression test missing"
grep -Fqs 'third clean confirmation completes' tools/r7m2test/R7SyncControllerTests.kt || fail "three-confirmation regression test missing"

if grep -Fqs 'quietRounds' "$ENGINE"; then fail "legacy quietRounds completion logic still present"; fi
if grep -Fqs 'bottomEvidence' "$ENGINE"; then fail "legacy bottomEvidence completion logic still present"; fi
if grep -Rqs 'android.permission.INTERNET' app/src/main/AndroidManifest.xml; then fail "INTERNET permission introduced"; fi

echo "R7_M2_COMPAT_PASS"
