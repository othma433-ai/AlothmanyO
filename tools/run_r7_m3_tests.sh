#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m3test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/ConversationVerifier.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/navigation/ConversationNavigator.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/navigation/SmartScrollController.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m3test/R7NavigationTests.kt" -include-runtime -d "$OUT/r7m3tests.jar"
java -jar "$OUT/r7m3tests.jar"
