#!/usr/bin/env bash
#
# Fetches a model named in models.lock and verifies it.
#
#   ./fetch-model.sh <staging-dir> <name>
#
# Pinned to a repository *revision*, not `main`: a GGUF re-uploaded under the
# same name is a different model, and the checksum would catch it only after
# the download.
set -euo pipefail

STAGING="${1:?usage: fetch-model.sh <staging-dir> <name>}"
NAME="${2:?usage: fetch-model.sh <staging-dir> <name>}"
LOCK="$(cd "$(dirname "$0")" && pwd)/models.lock"

read -r _ repo revision file bytes sha < <(awk -v n="$NAME" '$1 == n' "$LOCK")
[ -n "${file:-}" ] || { echo "no model named $NAME in $LOCK" >&2; exit 1; }

mkdir -p "$STAGING"
cd "$STAGING"
if [ ! -f "$file" ] || [ "$(stat -c %s "$file")" != "$bytes" ]; then
    echo "==> downloading $file ($((bytes / 1048576)) MB)"
    curl -fL --retry 3 -C - "https://huggingface.co/$repo/resolve/$revision/$file" -o "$file"
fi
echo "$sha  $file" | sha256sum -c --quiet
echo "$file verified"
