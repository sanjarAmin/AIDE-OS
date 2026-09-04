#!/usr/bin/env bash
#
# Builds the probe archive: the Analysis API code that runs *inside* the dex.
#
# **Why the probe is a separate archive and not test code.** The Analysis API
# is loaded at runtime by a PathClassLoader parented to the boot loader, so the
# instrumented test cannot be compiled against it -- the test's own classes come
# from a loader that archive has never heard of. Driving the API by reflection
# is not a workaround either: it is Kotlin DSL builders and lambdas with
# receivers, which reflection renders unreadable and unmaintainable.
#
# So the real work is written as ordinary Kotlin in probe/AnalysisProbe.kt,
# compiled here against the *relocated* jars, dexed, and shipped as a third
# archive on the same loader. The test then reflects over one method with String
# in and String out. See probe/AnalysisProbe.kt and FINDINGS.md section 13.
#
# This was done by hand while the session was still the open question, which
# made every re-measurement a retyped command line. It is a script now because
# the archive is an input to a test that reports timings: a probe rebuilt
# slightly differently is a benchmark that silently measures something else.
#
# Usage: build-probe.sh [--out FILE]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARS="${ANALYSIS_API_JARS:-$HOME/aide-os-spikes/analysisapi}"
KOTLINC="${KOTLINC_JARS:-$HOME/aide-os-spikes/kotlinc/jars}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
JAVA="${JAVA_HOME:-/opt/android-studio/jbr}/bin/java"
OUT="${1:-$JARS/analysis-probe.jar}"

MIN_API=30   # the same floor as the archive it rides beside

RELOCATED="$JARS/relocated"
[ -d "$RELOCATED" ] || { echo "run relocate.sh first" >&2; exit 1; }

D8="$(ls "$SDK"/build-tools/*/lib/d8.jar 2>/dev/null | sort | tail -1)"
ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort | tail -1)"
[ -n "$D8" ] && [ -n "$ANDROID_JAR" ] || { echo "no d8 or android.jar under $SDK" >&2; exit 1; }

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"

# **Compiled against the relocated jars, not the originals.** The probe calls
# `org.jetbrains.kotlin.com.intellij.openapi.util.Disposer`, which only exists
# after relocation -- building against the upstream jars would produce a probe
# referring to `com.intellij.*` and fail at link time on device, where the
# message is a NoClassDefFoundError for a package that was never shipped.
CP="$(ls "$RELOCATED"/*.jar | tr '\n' ':')"
CP="$CP$KOTLINC/kotlin-compiler-embeddable.jar:$KOTLINC/kotlin-stdlib.jar"
CP="$CP:$JARS/caffeine.jar:$JARS/kotlinx-serialization-core.jar"

echo "compiling the probe..."
"$JAVA" -Xmx4g -cp "$KOTLINC/kotlin-compiler-embeddable.jar:$KOTLINC/kotlin-stdlib.jar:$KOTLINC/kotlin-reflect.jar:$KOTLINC/kotlin-script-runtime.jar:$KOTLINC/kotlinx-coroutines-core-jvm.jar:$KOTLINC/annotations.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -cp "$CP" -d "$work/classes" \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaNonPublicApi \
  "$HERE"/probe/*.kt

classes="$(find "$work/classes" -name '*.class' | wc -l)"
[ "$classes" -gt 0 ] || { echo "the probe compiled to nothing" >&2; exit 1; }
echo "  $classes classes"

# --min-api matches the archive; --lib android.jar so d8 can see the platform
# types the stdlib references. The probe's own dependencies stay *out* of the
# dex -- they are already in the archives it is loaded beside, and a duplicate
# type is a d8 error rather than a shadowed class.
#
# **One --classpath per jar.** d8 does not split this argument on ':' the way
# javac and the JVM do; it treats the whole colon-joined string as a single
# filename and fails with a NoSuchFileException whose "missing" path is every
# jar concatenated -- which reads as a corrupted variable rather than a flag
# used wrongly.
cp_args=()
for entry in ${CP//:/ }; do cp_args+=(--classpath "$entry"); done

echo "dexing..."
"$JAVA" -cp "$D8" com.android.tools.r8.D8 \
  --min-api "$MIN_API" --lib "$ANDROID_JAR" \
  "${cp_args[@]}" \
  --output "$work/dex" \
  $(find "$work/classes" -name '*.class')

# Laid out like the archive beside it: classes.dex at the root is what
# PathClassLoader reads.
rm -f "$OUT"
( cd "$work/dex" && zip -q -X -r "$OUT" classes.dex )
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
