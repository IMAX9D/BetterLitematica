#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=8.6
EXPECTED=9631d53cf3e74bfa726893aee1f8994fee4e060c401335946dba2156f440f24c
TOOLS="$ROOT/.tools"
GRADLE="$TOOLS/gradle-$VERSION/bin/gradle"
command -v java >/dev/null || { echo 'A JDK is required; JDK 17 is recommended.' >&2; exit 1; }
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
if [[ $# -eq 0 ]]; then set -- coreCheck :fabric-1.20.1:build; fi
exec "$GRADLE" --console=plain --no-daemon "$@"
