#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/myhfguard-ocr-parser-test.jar"
kotlinc \
  "$ROOT/app/src/main/java/com/vitalink/app/util/MachineOcrParser.kt" \
  "$ROOT/tools/MachineOcrParserSelfTest.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
