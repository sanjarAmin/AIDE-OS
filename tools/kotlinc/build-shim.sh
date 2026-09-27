#!/usr/bin/env bash
#
# Compiles the compat shim that build-kotlinc-dex.py dexes alongside the Kotlin
# compiler. See that script's header for what each part of the shim is for.
#
# Everything is compiled at Java 11: d8 in build-tools 36 accepts class files up
# to version 65, and a modern JDK emits 69 by default.
#
# Usage: build-shim.sh [OUT_DIR]

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$here/shim"
out="${1:-$HOME/aide-os-spikes/kotlinc/shim-classes}"
javac="${JAVAC:-/opt/android-studio/jbr/bin/javac}"

rm -rf "$out"
mkdir -p "$out"

# javax.xml.stream belongs to the java.xml system module, and javac refuses to
# compile into a package a module already owns ("package exists in another
# module"). --patch-module says we are deliberately extending that module,
# which is exactly what we are doing -- just for a runtime that lacks it.
"$javac" --release 11 -Xlint:-options \
  --patch-module "java.xml=$src" \
  -d "$out" \
  $(find "$src/javax" -name '*.java')

# The rest is ordinary code in packages no module claims.
"$javac" --release 11 -Xlint:-options \
  -d "$out" \
  $(find "$src" -name '*.java' -not -path "$src/javax/*")

echo "compiled $(find "$out" -name '*.class' | wc -l) classes into $out"
