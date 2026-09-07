#!/usr/bin/env bash
#
# Builds the tree-sitter grammars this project cannot download.
#
# Every other grammar the editor uses is an AAR from
# com.itsaky.androidide.treesitter. That publisher ships no JavaScript grammar,
# so this compiles upstream's generated parser for the two ABIs the app ships
# and drops the result where :editor's jniLibs are.
#
# Reproducible from a clean machine: the source is a pinned release tarball
# verified against a recorded sha256, and the only other input is the NDK.
#
#   ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/treesitter/build-grammars.sh
#
# See FINDINGS.md next door for why the C# grammar is not built here.
set -euo pipefail

NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
[ -x "$CC" ] || { echo "no NDK clang at $CC; set ANDROID_NDK" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/editor/src/main/jniLibs"
ASSETS="$ROOT/editor/src/main/assets/treesitter"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One grammar per line: name  version  sha256-of-the-tarball
GRAMMARS="javascript 0.23.1 fc5b8f5a491a6db33ca4854b044b89363ff7615f4291977467f52c1b92a0c032"

# minSdk is 26, and both are 64-bit only because that is what :app filters to.
ABIS="aarch64-linux-android26:arm64-v8a x86_64-linux-android26:x86_64"

while read -r name version sha; do
  [ -n "$name" ] || continue
  repo="tree-sitter-$name"
  url="https://github.com/tree-sitter/$repo/archive/refs/tags/v$version.tar.gz"
  echo "==> $repo $version"
  curl -sSL -o "$WORK/$name.tar.gz" "$url"
  echo "$sha  $WORK/$name.tar.gz" | sha256sum -c - >/dev/null
  tar -xzf "$WORK/$name.tar.gz" -C "$WORK"
  src="$WORK/$repo-$version/src"

  # The symbol upstream's parser.c exports, and the class that calls it.
  entry="tree_sitter_$(echo "$name" | tr - _)"
  klass="$(echo "$name" | sed 's/^js$/JavaScript/;s/^javascript$/JavaScript/;s/^c-sharp$/CSharp/')Grammar"
  cat > "$WORK/jni-$name.c" <<JNI
#include <jni.h>

const void *$entry(void);

JNIEXPORT jlong JNICALL
Java_com_osamu_aide_editor_treesitter_${klass}_pointer(JNIEnv *env, jclass klass) {
    (void) env; (void) klass;
    return (jlong) (intptr_t) $entry();
}
JNI

  # scanner.c is optional; JavaScript has one, JSON does not.
  scanner=""
  [ -f "$src/scanner.c" ] && scanner="$src/scanner.c"

  for pair in $ABIS; do
    target="${pair%%:*}"; abi="${pair##*:}"
    mkdir -p "$OUT/$abi"
    # -s and --build-id=none so two builds of the same source produce the same
    # bytes: this lands in git, and a rebuild that changes only a build id
    # would show as a 371 KB diff every time.
    # max-page-size=16384 for Android 15's 16 KB pages.
    "$CC" --target="$target" -shared -fPIC -O2 -fvisibility=hidden \
      -Wl,-z,max-page-size=16384 -Wl,--build-id=none -s \
      -I"$src" "$src/parser.c" $scanner "$WORK/jni-$name.c" \
      -o "$OUT/$abi/lib$repo.so"
    echo "    $abi  $(du -h "$OUT/$abi/lib$repo.so" | cut -f1)"
  done

  # The query travels with the grammar it was written against; pairing a query
  # with a different revision is what editor/src/main/assets/treesitter/README.md
  # is about.
  mkdir -p "$ASSETS/$name"
  cp "$WORK/$repo-$version/queries/highlights.scm" "$ASSETS/$name/highlights.scm"

  # JSX is the same grammar and a *supplementary* query: upstream ships
  # highlights-jsx.scm expecting it to be applied on top of highlights.scm, so
  # the two are concatenated into one language rather than shipped separately.
  # tree-sitter applies one query per language.
  if [ -f "$WORK/$repo-$version/queries/highlights-jsx.scm" ]; then
    mkdir -p "$ASSETS/${name}x"
    cat "$WORK/$repo-$version/queries/highlights.scm" \
        "$WORK/$repo-$version/queries/highlights-jsx.scm" \
        > "$ASSETS/${name}x/highlights.scm"
    echo "    queries: $name, ${name}x"
  fi
done <<< "$GRAMMARS"

echo "done. :editor:connectedDebugAndroidTest is what says whether it works."
