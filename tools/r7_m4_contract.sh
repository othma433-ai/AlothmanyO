#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="$ROOT/app/src/main/java/com/alothmany/wa/data/AppDatabase.kt"
ENT="$ROOT/app/src/main/java/com/alothmany/wa/data/Entities.kt"
DAO="$ROOT/app/src/main/java/com/alothmany/wa/data/Daos.kt"
ENGINE="$ROOT/app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt"
OPS="$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/OperationRepository.kt"
EXTRACT="$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/ExtractionController.kt"

required=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/QueueModels.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/GroupRepository.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/LinkRepository.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/OperationRepository.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/CheckpointRepository.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/DeepExtractionStrategy.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/ExtractionController.kt"
)
for f in "${required[@]}"; do test -f "$f" || { echo "R7_M4_COMPAT_FAIL missing $f"; exit 1; }; done

grep -q 'version = 4' "$DB"
grep -q 'MIGRATION_3_4' "$DB"
grep -q 'extraction_queue_items' "$DB"
grep -q 'addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)' "$DB"
grep -q 'data class ExtractionQueueItemEntity' "$ENT"
grep -q 'interface ExtractionQueueDao' "$DAO"
grep -q 'ensureImmutableQueue' "$ENGINE"
grep -q 'QUEUE_FROZEN' "$ENGINE"
grep -q 'persistVerifiedViewport' "$ENGINE"
grep -q 'refreshJobCounters' "$ENGINE"
grep -q 'ExtractionQueueStatus.COMPLETED' "$ENGINE"
grep -q 'Checkpoint is intentionally saved only after every durable batch above commits' "$EXTRACT"
# Membership and order fields must never be updated after queue insertion.
if grep -E 'UPDATE extraction_queue_items SET .*(ordinal|targetPackage|expectedTitle|expectedIdentitySignature|discoveryProvenance|mode)' "$DAO"; then
  echo 'R7_M4_COMPAT_FAIL immutable queue identity fields are mutable'
  exit 1
fi
# No destructive migration shortcut.
if grep -Rqs 'fallbackToDestructiveMigration' "$ROOT/app/src/main/java"; then
  echo 'R7_M4_COMPAT_FAIL destructive migration present'
  exit 1
fi
# Existing privacy gates remain mandatory.
if grep -Rqs 'android.permission.INTERNET' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo 'R7_M4_COMPAT_FAIL INTERNET permission present'
  exit 1
fi

echo 'R7_M4_COMPAT_PASS'
