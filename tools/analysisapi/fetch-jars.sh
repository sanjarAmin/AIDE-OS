#!/usr/bin/env bash
#
# Rebuilds the Kotlin Analysis API jar set -- what Kotlin code intelligence
# needs and what the shipped compiler does not contain.
#
# The sibling of ../kotlinc/fetch-jars.sh and deliberately the same shape, with
# one difference that matters: these artifacts are **not on Maven Central**.
# JetBrains publishes them only to their own repository, so each row names its
# source and this script picks the host from that rather than trying both.
#
# Do not be tempted to resolve these with Maven or Gradle instead. Every
# -for-ide POM declares dependencies that 404 from the same repository, because
# they are shadowed into the jar itself; a resolver follows them and fails.
# Fetching by name is not a shortcut here, it is the only route that works.
#
# Usage: fetch-jars.sh [target-dir]   (default ~/aide-os-spikes/analysisapi)
set -euo pipefail

LOCK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/jars.lock"
TARGET="${1:-$HOME/aide-os-spikes/analysisapi}"

JETBRAINS="https://packages.jetbrains.team/maven/p/ij/intellij-dependencies"
CENTRAL="https://repo1.maven.org/maven2"

mkdir -p "$TARGET"
fetched=0 verified=0 failed=()

while read -r sha size name coord source; do
  case "$sha" in ''|\#*) continue ;; esac

  dest="$TARGET/$name"
  if [ -f "$dest" ] && [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$sha" ]; then
    verified=$((verified + 1))
    continue
  fi

  group="${coord%%:*}"; rest="${coord#*:}"
  artifact="${rest%%:*}"; version="${rest#*:}"
  path="${group//.//}/$artifact/$version/$artifact-$version.jar"

  case "$source" in
    jetbrains) base="$JETBRAINS" ;;
    central)   base="$CENTRAL" ;;
    *) echo "unknown source '$source' for $name" >&2; exit 1 ;;
  esac

  echo "fetching $name from $source"
  # -L because the JetBrains host answers 307 before serving the bytes; without
  # it curl writes a zero-length file and the checksum blames the mirror.
  if ! curl -sSfL -o "$dest" "$base/$path"; then
    failed+=("$name"); rm -f "$dest"; continue
  fi

  actual="$(sha256sum "$dest" | cut -d' ' -f1)"
  if [ "$actual" != "$sha" ]; then
    echo "  checksum mismatch: expected $sha, got $actual" >&2
    rm -f "$dest"; failed+=("$name"); continue
  fi
  fetched=$((fetched + 1))
done < "$LOCK"

echo
echo "verified $verified, fetched $fetched, into $TARGET"
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
