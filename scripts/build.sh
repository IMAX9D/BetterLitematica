#!/usr/bin/env bash
# JDK 25+: bash scripts/build.sh :fabric-1.21.11:build
# Build every release in targets.json: bash scripts/build.sh --all
# Install the matching JAR from fabric-1.20.1/build/libs or versions/<game>/build/libs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=9.6.0
EXPECTED=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
TOOLS="$ROOT/.tools"
GRADLE="$TOOLS/gradle-$VERSION/bin/gradle"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
command -v "$JAVA_BIN" >/dev/null && command -v "$JAVAC_BIN" >/dev/null || { echo 'JDK 25 or later is required. Set JAVA_HOME to a complete JDK.' >&2; exit 1; }
JDK_VERSION="$("$JAVAC_BIN" -version 2>&1)"
[[ "$JDK_VERSION" =~ ^javac\ ([0-9]+) ]] && (( BASH_REMATCH[1] >= 25 )) || { echo "JDK 25 or later is required; found $JDK_VERSION." >&2; exit 1; }
RUNTIME_VERSION="$("$JAVA_BIN" -version 2>&1)"
[[ "$RUNTIME_VERSION" =~ (openjdk|java)\ version\ \"([0-9]+) ]] && (( BASH_REMATCH[2] >= 25 )) || { echo "Gradle requires JDK 25 or later; found $RUNTIME_VERSION." >&2; exit 1; }
if [[ ! -x "$GRADLE" ]]; then
    mkdir -p "$TOOLS"
    ZIP="$TOOLS/gradle-$VERSION-bin.zip"
    if [[ ! -f "$ZIP" ]]; then
        command -v curl >/dev/null
        TMP="$ZIP.partial"
        trap 'rm -f "$TMP"' EXIT
        curl --fail --location --connect-timeout 15 --max-time 300 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" --output "$TMP"
        printf '%s  %s\n' "$EXPECTED" "$TMP" | sha256sum --check --status || { echo 'Gradle checksum mismatch.' >&2; exit 1; }
        mv "$TMP" "$ZIP"
        trap - EXIT
    fi
    printf '%s  %s\n' "$EXPECTED" "$ZIP" | sha256sum --check --status || { echo 'Gradle checksum mismatch.' >&2; exit 1; }
    STAGE="$(mktemp -d "$TOOLS/unpack.XXXXXX")"
    trap 'rm -rf "$STAGE"' EXIT
    [[ ! -e "$TOOLS/gradle-$VERSION" ]] || { echo 'An incomplete Gradle directory exists. Inspect it before retrying.' >&2; exit 1; }
    unzip -q "$ZIP" -d "$STAGE"
    mv "$STAGE/gradle-$VERSION" "$TOOLS/gradle-$VERSION"
    rmdir "$STAGE"; trap - EXIT
fi
cd "$ROOT"
if [[ $# -eq 0 ]]; then set -- :fabric-1.20.1:build; fi
if [[ ${1-} == --all ]]; then
    # Separate invocations bound memory instead of loading all Minecraft toolchains at once.
    command -v python3 >/dev/null || { echo 'Python 3 is required only for --all to read targets.json.' >&2; exit 1; }
    TARGETS="$(python3 -c 'import json,sys; print("\n".join(t["minecraft"] for t in json.load(open(sys.argv[1]))["targets"]))' "$ROOT/targets.json")"
    while IFS= read -r target; do
        "$GRADLE" --console=plain --no-daemon --max-workers=2 ":fabric-$target:build"
    done <<< "$TARGETS"
    exit 0
fi
exec "$GRADLE" --console=plain --no-daemon --max-workers=2 "$@"
