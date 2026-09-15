#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m1test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/NodeSnapshot.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/SnapshotSignature.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/ActionTargetEvidence.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/LiveNodeResolutionPolicy.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/observe/ScreenObservation.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/observe/ScreenStabilityDetector.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/observe/ScreenClassifier.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/adapter/WhatsAppAdapter.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/ActionContracts.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/CircuitBreaker.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/TraceModels.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/diagnostics/R7ShadowRuntime.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7coretest/R7FoundationTests.kt" -include-runtime -d "$OUT/r7m1tests.jar"
java -jar "$OUT/r7m1tests.jar"
