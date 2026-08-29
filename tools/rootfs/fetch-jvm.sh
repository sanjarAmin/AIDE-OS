#!/usr/bin/env bash
#
# Fetches a JVM that runs on Android, for spike R11.
#
# Termux builds OpenJDK against **Bionic**: the launcher is an ordinary Android
# ELF with `/system/bin/linker64` as its interpreter, which is the shape spike
# R9 established can be started from app storage. That is why M9 may not need a
# Linux rootfs at all -- see FINDINGS.md.
#
#   ./fetch-jvm.sh <staging-dir> [arch]        arch: aarch64 | x86_64
#
# Produces <staging>/jvm.tar, which unpacks to a Termux `usr` tree with the JDK
# at lib/jvm/java-21-openjdk.
set -euo pipefail

STAGING="${1:?usage: fetch-jvm.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o Packages

# openjdk-21 rather than 17 or 25: Gradle supports it, and 25 is newer than
# anything the Android toolchain is tested against.
echo "==> dependency closure"
python3 - openjdk-21 << 'PYTHON_EOF'
import re, sys

packages = {}
for block in open("Packages", encoding="utf-8").read().split("\n\n"):
    name = re.search(r"^Package: (.+)$", block, re.M)
    if not name:
        continue
    packages[name.group(1)] = {
        "depends": (re.search(r"^Depends: (.+)$", block, re.M) or type("", (), {"group": lambda s, n: ""})()).group(1),
        "file": (re.search(r"^Filename: (.+)$", block, re.M) or type("", (), {"group": lambda s, n: None})()).group(1),
        "sha": (re.search(r"^SHA256: (.+)$", block, re.M) or type("", (), {"group": lambda s, n: None})()).group(1),
    }

def dependencies(name):
    entry = packages.get(name)
    if not entry or not entry["depends"]:
        return []
    out = []
    for clause in entry["depends"].split(","):
        clause = clause.strip()
        if clause:
            out.append(re.split(r"[ (|]", clause.split("|")[0].strip())[0])
    return out

seen, order, queue = set(), [], list(sys.argv[1:])
while queue:
    name = queue.pop(0)
    if name in seen or name not in packages:
        continue
    seen.add(name)
    order.append(name)
    queue.extend(dependencies(name))

with open("closure.txt", "w", encoding="utf-8") as out:
    for name in order:
        out.write(f"{name} {packages[name]['file']} {packages[name]['sha']}\n")
print(f"    {len(order)} packages")
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
[ -x "$PREFIX/lib/jvm/java-21-openjdk/bin/java" ] || { echo "no java launcher in the closure" >&2; exit 1; }

echo "==> jvm.tar"
tar cf jvm.tar -C "$PREFIX" .

echo
echo "jvm.tar: $(du -h jvm.tar | cut -f1)   installed: $(du -sh "$PREFIX" | cut -f1)"
