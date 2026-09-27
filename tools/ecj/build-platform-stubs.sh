#!/usr/bin/env bash
#
# Builds platform-stubs.jar: the handful of java.lang.invoke bootstrap classes
# that a Java compiler must resolve but that android.jar does not contain.
#
# Why this is needed at all: on a desktop Android build the java.* platform
# comes from the JDK's own java.base and only android.* comes from android.jar.
# On-device there is no java.base, so anything the compiler expects from it has
# to be supplied. Without these two classes ECJ rejects every lambda and, at
# source level 9+, every string concatenation.
#
# Why a stub is honest rather than a fudge: D8 desugars the invokedynamic into
# an anonymous class at dex time, so the bootstrap method is never called and
# the reference does not survive into the APK. The bodies throw to make that
# explicit.
#
# This jar belongs on the compiler's -classpath, never on the runtime classpath.
#
# Usage: build-platform-stubs.sh [OUT_JAR]

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$here/stubs"
out="${1:-$HOME/aide-os-spikes/ecj/platform-stubs.jar}"
javac="${JAVAC:-/opt/android-studio/jbr/bin/javac}"
jar="${JAR:-/opt/android-studio/jbr/bin/jar}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# java.lang.invoke belongs to the java.base system module, and javac refuses to
# compile into a package a module already owns. --patch-module says we are
# deliberately extending it, which is exactly what we are doing.
#
# Release 11, not 8: d8 accepts class files up to version 65, and these have to
# be readable by ECJ, which caps at what its own release supports.
"$javac" --release 11 -Xlint:-options \
  --patch-module "java.base=$src" \
  -d "$work" \
  $(find "$src" -name '*.java')

mkdir -p "$(dirname "$out")"
"$jar" --create --file "$out" -C "$work" java

echo "wrote $out"
unzip -l "$out" | tail -n +4 | head -n -2
