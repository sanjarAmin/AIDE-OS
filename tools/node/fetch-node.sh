#!/usr/bin/env bash
#
# Fetches a Node.js that runs on Android, for M10.
#
# Same route as the JDK and clang, and for the same reason: Termux builds
# against **Bionic**, so `bin/node` is an ordinary Android ELF whose interpreter
# is `/system/bin/linker64` -- the one shape spike R9 established this app can
# start. The NodeSource and nodejs.org builds are glibc and cannot run here at
# all, which is why this is assembled rather than downloaded whole.
#
#   ./fetch-node.sh <staging-dir> [arch]        arch: aarch64 | x86_64
#
# Produces <staging>/node.tar, a Termux `usr` tree with the runtime at bin/node.
#
# **nodejs-lts, not nodejs.** The repo carries both; the LTS line is 24.x where
# the other is 26.x, and the same reasoning applied to the JDK -- Gradle
# supports 21, and 25 is newer than anything the Android toolchain is tested
# against -- applies to whatever a user's project depends on.
set -euo pipefail

STAGING="${1:?usage: fetch-jvm.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o Packages

# The dependency closure is walked from this one package; Termux's node pulls
# in libc++, openssl, c-ares, icu and libuv, none of which are named here.
echo "==> dependency closure"
python3 - nodejs-lts << 'PYTHON_EOF'
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
[ -x "$PREFIX/bin/node" ] || { echo "no node binary in the closure" >&2; exit 1; }

echo "==> node.tar"
tar cf node.tar -C "$PREFIX" .

echo
echo "node.tar: $(du -h node.tar | cut -f1)   installed: $(du -sh "$PREFIX" | cut -f1)"
