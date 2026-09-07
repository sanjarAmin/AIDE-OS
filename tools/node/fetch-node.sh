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

# **Two roots, because `nodejs-lts` does not contain npm.** Termux ships the
# runtime and `corepack` in one package and npm in another; a closure walked
# from the runtime alone gives a `bin/` holding exactly `node` and `corepack`,
# and every `npm install` in every project fails with "not found" for a reason
# that has nothing to do with this app. The rest of the closure -- libc++,
# openssl, c-ares, icu, libuv -- is pulled in by the walk and named nowhere.
echo "==> dependency closure"
python3 - nodejs-lts npm << 'PYTHON_EOF'
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
        "conflicts": (re.search(r"^Conflicts: (.+)$", block, re.M) or type("", (), {"group": lambda s, n: ""})()).group(1),
    }

def dependencies(name, roots):
    """Direct dependencies, resolving `a | b` in favour of a requested root.

    Taking the first alternative blindly is what produced an archive holding
    two mutually exclusive packages: npm declares `nodejs | nodejs-lts`, so a
    build asking for the LTS got `nodejs` as well, and whichever extracted
    last won. Preferring a root keeps the choice the caller already made.
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
        chosen = next((o for o in options if o in roots), options[0])
        out.append(chosen)
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

# **A closure that contradicts itself must not be packed.** Two packages that
# declare each other in `Conflicts` cannot both be installed, and a tar holding
# both is decided by extraction order -- which is how a build asking for the
# LTS runtime produced a 26.x one.
#
# Only *unversioned* conflicts count. `nodejs-lts` says plainly `Conflicts:
# nodejs`, which is absolute; `npm` says `Conflicts: nodejs-lts (<= 24.13.0)`,
# which is a lower bound the repo's own consistency already satisfies, and
# treating it as absolute rejects a closure that is perfectly sound.
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
[ -f "$PREFIX/lib/node_modules/npm/bin/npm-cli.js" ] || { echo "no npm in the closure" >&2; exit 1; }

# **Trimmed of what running JavaScript cannot use.** `include/` is 11 MB of
# node's C++ headers, there for building native addons -- which needs a compiler
# and a linker this launch cannot spawn anyway -- and `share/` is man pages.
#
# The rest is not reducible: the runtime binary is 43 MB and `libicudata` is
# 32 MB, and dropping the latter is not a size decision but a decision to break
# `Intl`. 118 MB -> ~106 MB.
echo "==> trimming"
rm -rf "$PREFIX/include" "$PREFIX/share"

echo "==> node.tar"
tar cf node.tar -C "$PREFIX" .

echo
echo "node.tar: $(du -h node.tar | cut -f1)   installed: $(du -sh "$PREFIX" | cut -f1)"
