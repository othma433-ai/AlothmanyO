#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m2test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/SyncModels.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/TerminalConsensus.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/SyncController.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/sync/GroupGrabberController.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m2test/R7SyncControllerTests.kt" -include-runtime -d "$OUT/r7m2tests.jar"
java -jar "$OUT/r7m2tests.jar"
