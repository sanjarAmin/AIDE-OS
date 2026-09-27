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

# Two repositories, tried in order. androidx artifacts are published only by
# Google, and the Compose runtime is one of them.
REPOS=(
  "https://repo1.maven.org/maven2"
  "https://dl.google.com/dl/android/maven2"
)

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

  # `@aar` means the artifact is an Android library and the jar this row names
  # is the classes.jar inside it. Nothing else about the row changes: the
  # checksum is still of the file that ends up on disk.
  packaging="jar"
  bare="$coord"
  case "$coord" in *@aar) packaging="aar"; bare="${coord%@aar}" ;; esac

  group="${bare%%:*}"; rest="${bare#*:}"
  artifact="${rest%%:*}"; version="${rest##*:}"
  path="${group//.//}/$artifact/$version/$artifact-$version.$packaging"

  echo "fetching $name  <-  $coord"
  ok=0
  for repo in "${REPOS[@]}"; do
    if curl -fL --retry 3 --progress-bar -o "$dest.download" "$repo/$path"; then ok=1; break; fi
  done
  if [ "$ok" != "1" ]; then
    echo "could not fetch $name from any repository ($path)" >&2
    exit 1
  fi

  if [ "$packaging" = "aar" ]; then
    # Extracted rather than repackaged: rebuilding the zip would change the
    # bytes and the checksum with them.
    unzip -p "$dest.download" classes.jar > "$dest.part"
    rm -f "$dest.download"
  else
    mv "$dest.download" "$dest.part"
  fi

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

jars.lock carries their checksums, so a copy can be *verified*; it cannot yet
be rebuilt. Copy them from a machine that has them, then re-run to confirm.
EOF
  exit 2
fi
