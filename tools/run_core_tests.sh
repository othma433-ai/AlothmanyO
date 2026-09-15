#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/coretest"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc \
  "$ROOT"/app/src/main/java/com/alothmany/wa/domain/*.kt \
  "$ROOT"/tools/coretest/CoreTests.kt \
  -include-runtime -d "$OUT/coretests.jar"
java -jar "$OUT/coretests.jar"
