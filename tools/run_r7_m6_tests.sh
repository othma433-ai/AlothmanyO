#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m6test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/CircuitBreaker.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/RunLifecycle.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/automation/Watchdog.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityEventBuffer.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/navigation/RecoveryController.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m6test/R7RecoveryTests.kt" -include-runtime -d "$OUT/r7m6tests.jar"
java -jar "$OUT/r7m6tests.jar"
