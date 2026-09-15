#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$ROOT/app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt"
STRATEGY="$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/NewOnlyExtractionStrategy.kt"
GROUP_REPO="$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/GroupRepository.kt"

for f in "$STRATEGY" "$ROOT/tools/run_r7_m5_tests.sh"; do
  test -f "$f" || { echo "R7_M5_COMPAT_FAIL missing $f"; exit 1; }
done

grep -q 'NewOnlyExtractionStrategy' "$ENGINE"
grep -q 'previousNewOnlyCheckpoint' "$ENGINE"
grep -q 'nextNewOnlyCheckpoint' "$ENGINE"
grep -q 'NEW_ONLY_BOUNDARY_CONFIRMED' "$ENGINE"
grep -q 'NEW_ONLY_HISTORICAL_TERMINAL_CONSENSUS' "$ENGINE"
grep -q 'updateNewOnlyBoundary' "$ENGINE"
grep -q 'PRIOR_CHECKPOINT_MATCH' "$STRATEGY"
grep -q 'UNREAD_BOUNDARY' "$STRATEGY"
grep -q 'HISTORICAL_NO_PROGRESS' "$STRATEGY"
grep -q 'viewportSignature' "$STRATEGY"
grep -q 'timestampContextHashes' "$STRATEGY"
grep -q 'urlFingerprints' "$STRATEGY"
grep -q 'legacy = true' "$STRATEGY"
grep -q 'updateNewOnlyBoundary' "$GROUP_REPO"

# The pre-R7-M5 anchor-only matcher must not remain in the engine.
if grep -q 'anchorOverlap\|anchorToken\|parseAnchors' "$ENGINE"; then
  echo 'R7_M5_COMPAT_FAIL legacy anchor-only boundary logic still present'
  exit 1
fi

# High-water commit must occur only after the completion/final-verification gates.
python3 - "$ENGINE" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
completion=s.find('if (!completionProven || scrolls >= 5000)')
commit=s.find('updateNewOnlyBoundary', completion)
final_verify=s.find('finalVerification', s.find('private suspend fun extractGroup'))
if min(completion, commit, final_verify) < 0 or not (final_verify < completion < commit):
    raise SystemExit('R7_M5_COMPAT_FAIL high-water commit ordering invalid')
print('R7_M5_COMMIT_ORDER_PASS')
PY

# Privacy invariants remain unchanged.
if grep -Rqs 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo 'R7_M5_COMPAT_FAIL INTERNET permission present'
  exit 1
fi

echo 'R7_M5_COMPAT_PASS'
