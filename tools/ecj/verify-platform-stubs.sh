#!/usr/bin/env bash
#
# The compiler writes these descriptors straight into the constant pool of every
# class containing a lambda, so a stub whose signature drifts from the JDK's
# would produce bytecode that resolves to nothing -- and D8 desugars by matching
# the bootstrap method, so it would fail there rather than anywhere obvious.
# This diffs every public member against a real JDK's.
#
# Usage: verify-platform-stubs.sh PLATFORM_STUBS_JAR

set -euo pipefail

stubs="${1:?usage: verify-platform-stubs.sh PLATFORM_STUBS_JAR}"
jbr="${JBR:-/opt/android-studio/jbr}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

"$jbr/bin/jimage" extract --include 'regex:^/java.base/java/lang/invoke/(LambdaMetafactory|StringConcatFactory).class' \
  --dir="$tmp/jdk" "$jbr/lib/modules" >/dev/null
unzip -q -o "$stubs" 'java/lang/invoke/*.class' -d "$tmp/ours"

status=0
for class in java/lang/invoke/LambdaMetafactory.class java/lang/invoke/StringConcatFactory.class; do
  # Public static members only: the JDK's have constructors, fields and private
  # helpers a stub has no reason to reproduce. What must match is the surface a
  # compiler emits calls against.
  members() {
    "$jbr/bin/javap" "$1" \
      | grep -E '^\s+public static' \
      | sed 's/ throws .*/;/' \
      | sort
  }
  if diff <(members "$tmp/jdk/java.base/$class") <(members "$tmp/ours/$class"); then
    echo "ok   $class"
  else
    echo "DIFF $class"
    status=1
  fi
done
exit $status
