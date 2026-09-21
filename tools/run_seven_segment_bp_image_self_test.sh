#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc \
  app/src/main/java/com/vitalink/app/util/SevenSegmentBpRecognizerCore.kt \
  tools/SevenSegmentBpImageSelfTest.kt \
  -include-runtime -d "$TMP/bp-seven-seg-test.jar"
java -jar "$TMP/bp-seven-seg-test.jar" "app/src/test/resources/vital_ocr_128_82_80.png"
