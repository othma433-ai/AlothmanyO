#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m8test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/domain/InviteStateClassifier.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/NodeSnapshot.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/SnapshotSignature.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/snapshot/ActionTargetEvidence.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/join/JoinEngine.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m8test/R7JoinEngineTests.kt" -include-runtime -d "$OUT/r7m8tests.jar"
java -jar "$OUT/r7m8tests.jar"
