#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m7test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/NodeSnapshot.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/SnapshotSignature.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/ActionTargetEvidence.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/observe/ScreenObservation.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/observe/ScreenClassifier.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/adapter/WhatsAppAdapter.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/SyncModels.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/GroupGrabberController.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/TerminalConsensus.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/navigation/SmartScrollController.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfile.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfileRepository.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/DiagnosticCapture.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/ReplayFixture.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m7test/R7ProfilesReplayTests.kt" -include-runtime -d "$OUT/r7m7tests.jar"
java -jar "$OUT/r7m7tests.jar"
