#!/usr/bin/env bash
#
# Builds a dex archive of the Kotlin Analysis API, loadable on ART.
#
# Simpler than ../kotlinc/build-kotlinc-dex.py, and the difference is the
# interesting part: that one rewrites java.lang.management and replaces four
# classes outright, because the *compiler* reaches for JDK APIs Android does not
# have. The Analysis API reaches for almost none -- no java.lang.management, no
# AWT, three classes touching javax.swing -- so nothing here needs a shim.
#
# What it does need is the relocation, which must have run first: see
# relocate.sh and FINDINGS.md §4.
#
# The archive is laid out like an APK -- classes.dex at the root, resources
# beside it -- because that is what PathClassLoader reads. META-INF/analysis-api
# carries the plugin descriptors the API registers itself through; an archive
# with the dex and without them loads every class and provides no services.
#
# Usage: build-dex.sh [--sdk DIR] [--out FILE]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARS="${ANALYSIS_API_JARS:-$HOME/aide-os-spikes/analysisapi}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
OUT="${1:-$JARS/analysis-api-2.2.10.zip}"
KOTLINC="${KOTLINC_JARS:-$HOME/aide-os-spikes/kotlinc/jars}"
COMPILER="$KOTLINC/kotlin-compiler-embeddable.jar"
JAVA="${JAVA_HOME:-/opt/android-studio/jbr}/bin/java"

# Matches ../kotlinc: aapt2's libbase and the compiler archive land on the same
# number, so the app gates on one version check rather than two.
MIN_API=30

RELOCATED="$JARS/relocated"
[ -d "$RELOCATED" ] || { echo "run relocate.sh first" >&2; exit 1; }

D8="$(ls "$SDK"/build-tools/*/lib/d8.jar 2>/dev/null | sort | tail -1)"
ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort | tail -1)"
[ -n "$D8" ] && [ -n "$ANDROID_JAR" ] || { echo "no d8 or android.jar under $SDK" >&2; exit 1; }

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/dex"

# The compiler jars are --classpath, not input: they are already dexed in the
# kotlinc archive, and dexing them again would put two copies of the platform on
# one device and make the loader's parent ambiguous.
classpath=()
for jar in "$KOTLINC"/*.jar; do classpath+=(--classpath "$jar"); done

echo "dexing (min-api $MIN_API)..."
"$JAVA" -Xmx8g -cp "$D8" com.android.tools.r8.D8 \
  --release --min-api "$MIN_API" \
  --lib "$ANDROID_JAR" \
  "${classpath[@]}" \
  --output "$work/dex" \
  "$RELOCATED"/*.jar "$JARS/caffeine.jar" "$JARS/kotlinx-serialization-core.jar"

stage="$work/stage"; mkdir -p "$stage"
cp "$work"/dex/*.dex "$stage"/

# The descriptors, **flattened**. Each one's xi:includes are substituted for
# their content at build time, because the IntelliJ platform shaded into the
# compiler resolves some of them and silently gives up on the ones that live in
# a different jar -- reporting the file it was *reading* rather than the include
# it could not find. flatten-descriptors.py has the detail.
python3 "$HERE/flatten-descriptors.py" "$stage" "$RELOCATED"/*.jar "$COMPILER"

descriptors="$(find "$stage/META-INF/analysis-api" -name '*.xml' 2>/dev/null | wc -l)"
if [ "$descriptors" -eq 0 ]; then
  echo "no analysis-api descriptors were staged -- the archive would register nothing" >&2
  exit 1
fi

# **Reproducible, deliberately.** d8 is deterministic -- two runs produce a
# byte-identical classes.dex -- but zip stores mtimes, so without this the
# archive's checksum changes every build and could never be pinned the way
# ../kotlinc/jars.lock pins its inputs. Fixed timestamp, sorted entries, and
# -X to drop the platform-specific extra fields.
find "$stage" -exec touch -t 198002010000 {} +
rm -f "$OUT"
(cd "$stage" && find . -type f | LC_ALL=C sort | zip -qX "$OUT" -@)
echo
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)), $descriptors descriptors"
unzip -l "$OUT" | tail -3
