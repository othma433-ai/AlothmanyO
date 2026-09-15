#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m5test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/domain/UrlNormalizer.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/NewOnlyExtractionStrategy.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m5test/R7NewOnlyTests.kt" -include-runtime -d "$OUT/r7m5tests.jar"
java -jar "$OUT/r7m5tests.jar"
