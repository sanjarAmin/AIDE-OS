#!/usr/bin/env bash
#
# Assembles a clang toolchain that runs on Android, from Termux's package repo.
#
# The whole point of this script is that it exists. `tools/kotlinc` has a
# ~100 MB input set that nothing in the repo assembles, and that gap is a
# standing risk noted in CLAUDE.md; this toolchain is six times larger, so
# reproducing it by hand was never an option.
#
# Termux is used rather than the NDK because NDK clang is built for glibc
# hosts. Termux's is built for Bionic, for Android, as a PIE binary that the
# platform's own linker can start -- which is exactly what spike R9 established
# is the only way to run a downloaded binary at all. See FINDINGS.md.
#
#   ./fetch-toolchain.sh <staging-dir> [arch]
#
# Produces <staging-dir>/toolchain.tar, which unpacks to `usr/`. Install it by
# extracting *from inside the app* -- see FINDINGS.md finding 4, which is not
# optional and not obvious.
set -euo pipefail

STAGING="${1:?usage: fetch-toolchain.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"

# The roots. Everything else arrives through the dependency walk below.
#
#   clang, lld       the driver and the linker
#   libllvm          139 MB, and where essentially all of clang's code lives:
#                    `clang-21` itself is a 126 KB stub against it
#   libcompiler-rt   `libclang_rt.builtins-*.a`, which every link needs
#   ndk-sysroot      the platform headers and stub libraries
#   libc++           only for the C++ headers; the runtime comes from the app
WANTED=(clang lld libllvm libcompiler-rt ndk-sysroot libc++)

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o Packages

# The closure walk. `Depends:` is a comma-separated list whose entries carry
# version constraints in parentheses; only the name is needed, because the
# repo holds exactly one version of each package.
python3 - "$ARCH" "${WANTED[@]}" << 'PYTHON_EOF'
import re, sys

arch, wanted = sys.argv[1], sys.argv[2:]

packages, current = {}, {}
for line in open("Packages", encoding="utf-8"):
    line = line.rstrip("\n")
    if not line:
        if "Package" in current:
            packages[current["Package"]] = current
        current = {}
    elif not line.startswith(" ") and ":" in line:
        key, value = line.split(":", 1)
        current[key] = value.strip()
if "Package" in current:
    packages[current["Package"]] = current

def dependencies(name):
    entry = packages.get(name)
    if entry is None:
        return []
    names = []
    for clause in entry.get("Depends", "").split(","):
        clause = clause.strip()
        if not clause:
            continue
        # "libllvm (>= 21.1.8)" -> "libllvm"; alternatives ("a | b") take the
        # first, which is what apt does when nothing is installed yet.
        names.append(re.split(r"[ (|]", clause.split("|")[0].strip())[0])
    return names

seen, order, queue = set(), [], list(wanted)
while queue:
    name = queue.pop(0)
    if name in seen:
        continue
    if name not in packages:
        # Not fatal: Termux's Depends name virtual packages the repo does not
        # carry, and the build is the judge of whether one was really needed.
        print(f"    (skipping {name}: not in the index)", file=sys.stderr)
        continue
    seen.add(name)
    order.append(name)
    queue.extend(dependencies(name))

with open("closure.txt", "w", encoding="utf-8") as out:
    for name in order:
        out.write(f"{name} {packages[name]['Filename']} {packages[name]['SHA256']}\n")
print(f"    {len(order)} packages")
PYTHON_EOF

echo "==> downloading"
while read -r name path sha; do
    file="$(basename "$path")"
    [ -f "$file" ] || curl -fsSL "$REPO/$path" -o "$file"
    echo "$sha  $file" | sha256sum -c --quiet
done < closure.txt
# Written so `sha256sum -c CHECKSUMS.sha256` works in this directory,
# not merely as a record: a checksum file nobody can run is decoration.
awk '{n = split($2, p, "/"); print $3"  "p[n]}' closure.txt | sort -k2 > CHECKSUMS.sha256

echo "==> extracting"
# `ar x` then `tar`: a .deb is an ar archive of debian-binary, control.tar.xz
# and data.tar.xz. dpkg is not assumed to be installed on the host.
rm -rf root && mkdir root
while read -r _ path _; do
    file="$(basename "$path")"
    (cd root && ar x "../$file" data.tar.xz && tar xf data.tar.xz && rm data.tar.xz)
done < closure.txt

# Termux packages unpack to their own absolute prefix. Everything inside is
# relocatable -- `LD_LIBRARY_PATH` and clang's `--sysroot`/`-resource-dir`
# cover all of it -- so the prefix is simply dropped.
PREFIX=root/data/data/com.termux/files/usr
[ -d "$PREFIX" ] || { echo "unexpected package layout under root/" >&2; exit 1; }

echo "==> toolchain.tar"
# **Not zip, and not adb push.** The tree holds ~900 symlinks, and both drop
# them: the result is either a silent 3x size increase or missing files. tar is
# also what the device unpacks with, so this is the format end to end.
tar cf toolchain.tar -C "$(dirname "$PREFIX")" usr

echo
echo "toolchain.tar: $(du -h toolchain.tar | cut -f1)"
# Not run here even when the architecture matches: these are Bionic
# binaries, so a glibc host cannot start them. The device is the only place
# this can be checked, which is what `spike/clang` is for.
echo "clang: $("$PREFIX/bin/clang-21" --version 2>&1 | head -1)"
