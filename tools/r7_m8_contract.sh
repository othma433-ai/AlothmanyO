#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JOIN="$ROOT/app/src/main/java/com/alothmany/wa/r7/join/JoinEngine.kt"
MODELS="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/UiActionModels.kt"
BRIDGE="$ROOT/app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt"
ACTION="$ROOT/app/src/main/java/com/alothmany/wa/automation/ActionAutomationEngine.kt"

for f in "$JOIN" "$MODELS" "$BRIDGE" "$ACTION" "$ROOT/tools/run_r7_m8_tests.sh"; do
  test -f "$f" || { echo "R7_M8_COMPAT_FAIL missing $f"; exit 1; }
done

for token in 'JoinTargetResolver' 'JoinPostconditionVerifier' 'JoinTargetIdentityGuard' 'JoinActionTarget' 'APPROVAL_REQUEST' 'COMMUNITY_VIEW'; do
  grep -q "$token" "$JOIN" || { echo "R7_M8_COMPAT_FAIL join invariant missing $token"; exit 1; }
done

grep -q 'val target: JoinActionTarget?' "$MODELS" || { echo 'R7_M8_COMPAT_FAIL PreparedInviteAction target missing'; exit 1; }
grep -q 'prepareInviteAction(8_000)' "$ACTION" || { echo 'R7_M8_COMPAT_FAIL join does not prepare target'; exit 1; }
grep -q 'performInviteAction(prepared)' "$ACTION" || { echo 'R7_M8_COMPAT_FAIL prepared target not forwarded'; exit 1; }
grep -q 'ACTION_TARGET_UNRESOLVED' "$ACTION" || { echo 'R7_M8_COMPAT_FAIL unresolved target is not fail-closed'; exit 1; }
if grep -q 'performInviteAction()' "$ACTION"; then
  echo 'R7_M8_COMPAT_FAIL legacy join call re-searches after classification'
  exit 1
fi

for token in 'AndroidAccessibilityGateway' 'dispatchOnResolvedTarget' 'clickReacquiredJoinNode' 'joinPostconditionVerifier.verify' 'Community view transition did not expose a verified Join community target'; do
  grep -q "$token" "$BRIDGE" || { echo "R7_M8_COMPAT_FAIL bridge invariant missing $token"; exit 1; }
done

# The first-stage join action must use the PreparedInviteAction evidence, not search by text again.
python - "$BRIDGE" <<'PY'
from pathlib import Path
import sys
text=Path(sys.argv[1]).read_text()
start=text.index('suspend fun performInviteAction(prepared: PreparedInviteAction')
end=text.index('suspend fun setMessageText', start)
body=text[start:end]
for forbidden in ['findActionTarget(JOIN_', 'findActionTarget(tokens)', 'JOIN_DIRECT_TOKENS', 'JOIN_APPROVAL_TOKENS']:
    if forbidden in body:
        raise SystemExit(f'R7_M8_COMPAT_FAIL join re-search present: {forbidden}')
if 'prepared.target' not in body or 'dispatchPreparedJoinTarget(target)' not in body:
    raise SystemExit('R7_M8_COMPAT_FAIL prepared target evidence not used')
if 'joinPostconditionVerifier.verify' not in body:
    raise SystemExit('R7_M8_COMPAT_FAIL postcondition verifier missing')
print('R7_M8_JOIN_FLOW_PASS')
PY

if grep -Rqs 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo 'R7_M8_COMPAT_FAIL INTERNET permission present'
  exit 1
fi

echo 'R7_M8_COMPAT_PASS'
