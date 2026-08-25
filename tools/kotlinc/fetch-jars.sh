#!/usr/bin/env bash
#
# Rebuilds the Kotlin compiler jar set that build-kotlinc-dex.py consumes.
#
# The set used to exist only in ~/aide-os-spikes/kotlinc/jars on one machine,
# with nothing in the repository able to recreate it -- a single-machine
# dependency standing in front of M4. jars.lock is the inventory; this script
# downloads every row that carries a Maven coordinate and checks it against the
# recorded sha256, so a mirror serving something else fails loudly.
#
# Usage: fetch-jars.sh [target-dir]        (default ~/aide-os-spikes/kotlinc/jars)
set -euo pipefail

LOCK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/jars.lock"
TARGET="${1:-$HOME/aide-os-spikes/kotlinc/jars}"
CENTRAL="https://repo1.maven.org/maven2"

mkdir -p "$TARGET"
fetched=0 verified=0 unpinned=()

while read -r sha size name coord; do
  case "$sha" in ''|\#*) continue ;; esac

  if [ "$coord" = "UNPINNED" ]; then
    unpinned+=("$name")
    continue
  fi

  dest="$TARGET/$name"
  if [ -f "$dest" ] && [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$sha" ]; then
    verified=$((verified + 1))
    continue
  fi

  group="${coord%%:*}"; rest="${coord#*:}"
  artifact="${rest%%:*}"; version="${rest##*:}"
  url="$CENTRAL/${group//.//}/$artifact/$version/$artifact-$version.jar"

  echo "fetching $name  <-  $coord"
  curl -fL --retry 3 --progress-bar -o "$dest.part" "$url"

  got="$(sha256sum "$dest.part" | cut -d' ' -f1)"
  if [ "$got" != "$sha" ]; then
    rm -f "$dest.part"
    echo "CHECKSUM MISMATCH for $name" >&2
    echo "  expected $sha" >&2
    echo "  got      $got" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  fetched=$((fetched + 1))
done < "$LOCK"

echo
echo "fetched $fetched, already present and verified $verified"

if [ ${#unpinned[@]} -gt 0 ]; then
  cat >&2 <<EOF

Still not reproducible (${#unpinned[@]}):
$(printf '  %s\n' "${unpinned[@]}")

These are repackaged rather than published as-is -- compose-runtime.jar is an
AAR's classes.jar, and the coroutines jar matches no released artifact byte for
byte. jars.lock carries their checksums, so a copy can be *verified*; it cannot
yet be rebuilt. Copy them from a machine that has them, then re-run to confirm.
EOF
  exit 2
fi
