#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/core-check"
mkdir -p "$OUT/classes"
find "$ROOT/core/src/main/java" "$ROOT/blueprint-io/src/main/java" "$ROOT/runtime/src/main/java" "$ROOT/selftest/src/main/java" -name '*.java' -print | LC_ALL=C sort > "$OUT/sources.txt"
# javac argument files require quoting paths containing spaces.
python3 - "$OUT/sources.txt" <<'PY_QUOTE'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);p.write_text('\n'.join('"'+x.replace('\\','\\\\').replace('"','\\"')+'"' for x in p.read_text().splitlines())+'\n')
PY_QUOTE
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$OUT/classes" "@$OUT/sources.txt"
java -ea -Xmx1G -cp "$OUT/classes" dev.betterlitematica.selftest.CoreTests | tee "$OUT/results.txt"
