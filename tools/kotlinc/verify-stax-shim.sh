#!/usr/bin/env bash
#
# The shim's javax.xml.stream types are linked against by already-compiled
# aalto bytecode, by exact descriptor -- a wrong parameter type or a missing
# method surfaces as a verify error deep inside XML parsing, not as a build
# failure. This diffs every member against a real JDK's.
#
# Usage: verify-stax-shim.sh SHIM_CLASSES_DIR

set -euo pipefail

shim="${1:?usage: verify-stax-shim.sh SHIM_CLASSES_DIR}"
jbr="${JBR:-/opt/android-studio/jbr}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

"$jbr/bin/jimage" extract --include 'regex:^/java.xml/javax/xml/stream/.*' \
  --dir="$tmp" "$jbr/lib/modules" >/dev/null

status=0
for class in $(cd "$shim" && find javax/xml/stream -maxdepth 1 -name '*.class' -not -name 'package-info.class'); do
  # -p for private members, sorted because javac and the JDK build need not
  # agree on declaration order. The JDK ships without SourceFile attributes,
  # so drop the "Compiled from" line javap prints for ours.
  members() { "$jbr/bin/javap" -p "$1" | grep -v '^Compiled from ' | sort; }
  if diff <(members "$tmp/java.xml/$class") <(members "$shim/$class"); then
    echo "ok   $class"
  else
    echo "DIFF $class"
    status=1
  fi
done
exit $status
