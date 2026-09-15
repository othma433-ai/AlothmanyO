#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m9test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityEventBuffer.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityHostCoordinator.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m9test/R7AccessibilityHostTests.kt" -include-runtime -d "$OUT/r7m9tests.jar"
java -jar "$OUT/r7m9tests.jar"
