#!/usr/bin/env bash
#
# Fetches a CPython that runs on Android.
#
# Same route as the JDK, clang, node and mono, and for the same reason: Termux
# builds against **Bionic**, so `bin/python3.x` is an ordinary Android ELF whose
# interpreter is `/system/bin/linker64` -- the one shape spike R9 established
# this app can start. python.org publishes no Android build at all, and the
# manylinux wheels everything else is built from are glibc.
#
#   ./fetch-python.sh <staging-dir> [arch]       arch: aarch64 | x86_64
#
# Produces <staging>/python.tar, a Termux `usr` tree with the runtime at
# bin/python3.
#
# **Two roots, `python` and `python-pip`.** Termux ships them separately, for
# the same reason it ships `nodejs-lts` and `npm` separately, and a closure
# walked from the runtime alone gives a tree where `import pip` fails and every
# attempt to install anything reports a missing module rather than a missing
# package. `tools/node/fetch-node.sh` learned this the hard way first.
set -euo pipefail

STAGING="${1:?usage: fetch-python.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o Packages

echo "==> dependency closure"
python3 - python python-pip << 'PYTHON_EOF'
import re, sys

packages = {}
for block in open("Packages", encoding="utf-8").read().split("\n\n"):
    name = re.search(r"^Package: (.+)$", block, re.M)
    if not name:
        continue
    def field(key):
        found = re.search(rf"^{key}: (.+)$", block, re.M)
        return found.group(1) if found else ""
    packages[name.group(1)] = {
        "depends": field("Depends"),
        "file": field("Filename"),
        "sha": field("SHA256"),
        "conflicts": field("Conflicts"),
    }

def dependencies(name, roots):
    """Direct dependencies, resolving `a | b` in favour of a requested root.

    The same rule `fetch-node.sh` arrived at: taking the first alternative
    blindly is what produced an archive holding two mutually exclusive
    packages. Preferring a root keeps the choice the caller already made.
    """
    entry = packages.get(name)
    if not entry or not entry["depends"]:
        return []
    out = []
    for clause in entry["depends"].split(","):
        clause = clause.strip()
        if not clause:
            continue
        options = [re.split(r"[ (]", part.strip())[0] for part in clause.split("|")]
        out.append(next((o for o in options if o in roots), options[0]))
    return out

roots = set(sys.argv[1:])
seen, order, queue = set(), [], list(sys.argv[1:])
while queue:
    name = queue.pop(0)
    if name in seen or name not in packages:
        continue
    seen.add(name)
    order.append(name)
    queue.extend(dependencies(name, roots))

# A closure that contradicts itself must not be packed; only *unversioned*
# conflicts count. See `fetch-node.sh` for the reasoning and the bug.
for name in order:
    for clause in packages[name]["conflicts"].split(","):
        clause = clause.strip()
        if not clause or "(" in clause:
            continue
        other = clause.split()[0]
        if other in seen:
            sys.exit(f"    {name} conflicts with {other}; the closure is incoherent")

with open("closure.txt", "w", encoding="utf-8") as out:
    for name in order:
        out.write(f"{name} {packages[name]['file']} {packages[name]['sha']}\n")
print(f"    {len(order)} packages: {' '.join(order)}")
PYTHON_EOF

echo "==> downloading"
# Note: not `read -r name path sha`. In zsh `path` is tied to `PATH`, and
# reading into it empties the command search path for the rest of the loop --
# which presents as `curl: command not found` several lines later.
while read -r name deb sha; do
    file="${deb##*/}"
    [ -f "$file" ] || curl -fsSL "$REPO/$deb" -o "$file"
    echo "$sha  $file" | sha256sum -c --quiet
done < closure.txt

echo "==> extracting"
rm -rf root && mkdir root
while read -r name deb sha; do
    file="${deb##*/}"
    (cd root && ar x "../$file" data.tar.xz && tar xf data.tar.xz && rm data.tar.xz)
done < closure.txt

PREFIX=root/data/data/com.termux/files/usr
[ -d "$PREFIX" ] || { echo "unexpected package layout" >&2; exit 1; }

# The real binary, not the `python3` symlink: the version is in its name and
# `PythonToolchain` has to find it without being told which one arrived.
#
# Matched by regex and not by `python3.*`, which also catches `python3.14-config`
# -- a shell script, and the first thing `find` returned. The failure was two
# steps later ("no standard library"), naming neither the glob nor the script.
RUNTIME="$(find "$PREFIX/bin" -maxdepth 1 -regex '.*/python3\.[0-9]+' -type f -perm -u+x | head -1)"
[ -n "$RUNTIME" ] || { echo "no python binary in the closure" >&2; exit 1; }
VERSION="$(basename "$RUNTIME" | sed 's/^python//')"
# The full version, for the archive's name and the release tag. Read from the
# package index rather than from the binary, because the binary only knows
# `3.14` and the release is tagged with the patch level.
VERSION_FULL="$(grep -A20 '^Package: python$' Packages | grep -m1 '^Version:' | sed 's/^Version: //;s/-[0-9]*$//')"
echo "    runtime: $(basename "$RUNTIME")   version: $VERSION_FULL"

[ -d "$PREFIX/lib/python$VERSION" ] || { echo "no standard library" >&2; exit 1; }
[ -d "$PREFIX/lib/python$VERSION/site-packages/pip" ] || { echo "no pip in the closure" >&2; exit 1; }

# **Trimmed of what running Python cannot use.**
#
#  - `include/` is CPython's C headers, there for building extension modules --
#    which needs a compiler *and the ability to spawn a linker*, and clang here
#    can do neither (`tools/clang/FINDINGS.md`). Any wheel needing them was
#    never going to build on device regardless.
#  - `share/` is man pages and terminfo for the interactive shell, which this
#    app does not present.
#  - `test/` and `idlelib` are CPython's own test suite and its Tk IDE. The
#    first is 25 MB of fixtures, the second cannot start without Tk, which is a
#    separate package this closure deliberately does not pull in.
#  - `__pycache__` everywhere: the `.pyc` files are rebuilt on first import and
#    are **not portable across the install path**, which is not where they were
#    written. Shipping them is shipping a cache that is wrong.
#  - `*.a` and `config-*` are for linking against libpython, which is the same
#    dead end as `include/`.
echo "==> trimming"
rm -rf "$PREFIX/include" "$PREFIX/share"
rm -rf "$PREFIX/lib/python$VERSION/test" "$PREFIX/lib/python$VERSION/idlelib"
rm -rf "$PREFIX/lib/python$VERSION/config-"*
find "$PREFIX" -name '__pycache__' -type d -prune -exec rm -rf {} +
find "$PREFIX" -name '*.a' -delete

echo "==> python.tar"
# A tar, not a zip: `bin/python3` is a **symlink** to the versioned binary, and
# `adb push` of a tree drops every symlink (`tools/clang/FINDINGS.md` §4). The
# archive moves as a tar and is unpacked on the device, which is the only way
# the link survives -- the same constraint mono has.
#
# **Reproducible, because the result is pinned by SHA-1.** `ToolchainComponent`
# records a checksum of the published `.tar.gz`, so two people running this
# script have to produce the same bytes or the pin is a number only its author
# can verify. Directory order, uid/gid, mtimes and gzip's embedded filename and
# timestamp are all sources of drift, and all four are nailed down here.
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 \
    -cf python.tar -C "$PREFIX" .

# -n so gzip embeds neither the name nor the mtime; -9 to match what is
# published. Both halves are kept: the `.tar` is what
# `gradle/stage-device-archives.gradle.kts` pushes for the instrumented tests,
# and the `.tar.gz` is what the release carries and the pin describes.
gzip -9 -n -c python.tar > "python-$VERSION_FULL-$ARCH.tar.gz"

RELEASE="python-$VERSION_FULL-$ARCH.tar.gz"
echo
echo "$RELEASE: $(du -h "$RELEASE" | cut -f1)   installed: $(du -sh "$PREFIX" | cut -f1)"
echo
echo "The pin for ToolchainComponent.python(\"$ARCH\"):"
echo "    sha1        = \"$(sha1sum "$RELEASE" | awk '{print $1}')\""
echo "    archiveBytes = $(stat -c%s "$RELEASE")L"
echo
echo "Upload $RELEASE to the release tagged python-$VERSION_FULL."
