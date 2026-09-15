#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/r7m4test"
rm -rf "$OUT" && mkdir -p "$OUT"
SOURCES=(
  "$ROOT/app/src/main/java/com/alothmany/wa/domain/UrlNormalizer.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/persistence/QueueModels.kt"
  "$ROOT/app/src/main/java/com/alothmany/wa/r7/extraction/DeepExtractionStrategy.kt"
)
kotlinc "${SOURCES[@]}" "$ROOT/tools/r7m4test/R7PersistenceTests.kt" -include-runtime -d "$OUT/r7m4tests.jar"
java -jar "$OUT/r7m4tests.jar"
