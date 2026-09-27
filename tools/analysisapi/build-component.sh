#!/usr/bin/env bash
#
# Packages the Analysis API and its backend as one installable component.
#
# **Two archives, one component, and they are not interchangeable.** The
# Analysis API archive is a dex archive -- classes.dex at the root with the
# plugin descriptors beside it -- and the backend is a second dex archive
# compiled against it. Both go on the same flat classloader at runtime, so
# shipping them separately would let a device end up with one and not the
# other, which fails as a ClassNotFoundException for a class that is simply in
# the archive nobody downloaded.
#
# The same shape as the Kotlin compiler component, which ships kotlinc.jar and
# kotlin-stdlib.jar together for the same reason: the compiler cannot start
# without a stdlib to put in its kotlin-home.
#
# Prints the sha1 and byte count the ToolchainComponent has to pin. Those are
# not optional -- the pin is what stops the archive behind a release URL
# changing under a user.
#
# Usage: build-component.sh [--out FILE]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARS="${ANALYSIS_API_JARS:-$HOME/aide-os-spikes/analysisapi}"
VERSION="${ANALYSIS_API_VERSION:-2.2.10}"
OUT="${1:-$JARS/kotlin-analysis-$VERSION.zip}"

API="$JARS/analysis-api-$VERSION.zip"
BACKEND="$JARS/analysis-probe.jar"
[ -f "$API" ] || { echo "no $API -- run build-dex.sh" >&2; exit 1; }

# **Built here rather than assumed.** Packaging a stale backend has happened
# twice: the archive is valid, installs cleanly, and fails at runtime as a
# NoSuchMethodException for a method that is plainly in the source -- because
# the source is not what was packaged. The backend is cheap to build, so build
# it, and the archive can only ever contain the code beside it.
echo "building the backend..."
"$HERE/build-probe.sh" >/dev/null
[ -f "$BACKEND" ] || { echo "build-probe.sh produced no $BACKEND" >&2; exit 1; }

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cp "$API" "$work/analysis-api.jar"
cp "$BACKEND" "$work/analysis-backend.jar"

# -X and a fixed timestamp: the same inputs have to produce the same bytes, or
# the sha1 below is a checksum of the moment it was built rather than of the
# archive. tools/kotlinc does the same and for the same reason.
find "$work" -exec touch -t 198001010000.00 {} +
rm -f "$OUT"
( cd "$work" && zip -q -X -r "$OUT" analysis-api.jar analysis-backend.jar )

echo "wrote $OUT"
echo "  archiveSha1  = \"$(sha1sum "$OUT" | cut -d' ' -f1)\""
echo "  archiveBytes = $(stat -c%s "$OUT")L"
