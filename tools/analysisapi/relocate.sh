#!/usr/bin/env bash
#
# Rewrites the Analysis API jars onto the compiler's relocated namespace.
#
# `kotlin-compiler-embeddable` shades the IntelliJ platform, guava,
# opentelemetry and picocontainer under `org/jetbrains/kotlin/`; the Analysis
# API is published against the unshaded originals. FINDINGS.md §4 has the
# reasoning and §5 what the rewrite has to reach beyond the constant pool.
#
# The ASM this needs is inside the compiler jar itself, so there is no
# dependency to install -- but that jar must be present, which means
# ../kotlinc/fetch-jars.sh has been run.
#
# Usage: relocate.sh [jar-dir] [out-dir]
#   defaults: ~/aide-os-spikes/analysisapi and <jar-dir>/relocated
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARS="${1:-$HOME/aide-os-spikes/analysisapi}"
OUT="${2:-$JARS/relocated}"
COMPILER="${KOTLINC_JARS:-$HOME/aide-os-spikes/kotlinc/jars}/kotlin-compiler-embeddable.jar"
JAVA="${JAVA_HOME:-/opt/android-studio/jbr}/bin/java"

if [ ! -f "$COMPILER" ]; then
  echo "missing $COMPILER -- run ../kotlinc/fetch-jars.sh first" >&2
  exit 1
fi

rm -rf "$OUT"; mkdir -p "$OUT"
count=0
for jar in "$JARS"/*-for-ide.jar; do
  [ -e "$jar" ] || { echo "no -for-ide jars in $JARS -- run fetch-jars.sh first" >&2; exit 1; }
  "$JAVA" -cp "$COMPILER" "$HERE/Relocate.java" "$jar" "$OUT/$(basename "$jar")"
  count=$((count + 1))
done

# The check that matters. A partial relocation still links and still runs, and
# fails later in whichever of the four ways FINDINGS.md §5 lists -- so this
# refuses to leave a half-rewritten set on disk.
echo
python3 - "$OUT" <<'PY'
import pathlib, re, sys
OUT = pathlib.Path(sys.argv[1])
RELOC = b"org/jetbrains/kotlin/"
SHADED = [b"com/intellij/", b"com/google/common/", b"io/opentelemetry/",
          b"org/picocontainer/", b"kotlinx/collections/immutable/"]
import zipfile
ok = bad = 0
offenders = set()
for jar in OUT.glob("*.jar"):
    with zipfile.ZipFile(jar) as z:
        for entry in z.namelist():
            if not entry.endswith(".class"):
                continue
            data = z.read(entry)
            for shaded in SHADED:
                for m in re.finditer(re.escape(shaded), data):
                    start = m.start()
                    if start >= len(RELOC) and data[start - len(RELOC):start] == RELOC:
                        ok += 1
                    else:
                        bad += 1
                        offenders.add(f"{jar.name}:{entry}")
print(f"relocated references: {ok}")
if bad:
    print(f"UN-RELOCATED: {bad} in {len(offenders)} classes", file=sys.stderr)
    for o in sorted(offenders)[:10]:
        print("   ", o, file=sys.stderr)
    sys.exit(1)
print("no un-relocated references remain")
PY
echo "relocated $count jars into $OUT"
