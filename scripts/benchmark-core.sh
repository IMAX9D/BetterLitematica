#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
bash "$ROOT/scripts/test-core.sh"
java -ea -Xmx1G -cp "$ROOT/build/core-check/classes" dev.betterlitematica.selftest.ImportBenchmark | tee "$ROOT/build/core-check/benchmark.txt"
