#!/usr/bin/env bash
#
# Fetches a C# toolchain that runs on Android, for M10.
#
# **Mono, because the .NET SDK is not available by this route.** The roadmap
# words M10's second half as ".NET SDK (experimental)"; Termux publishes no
# `dotnet` package, and Microsoft's builds are glibc and cannot start here at
# all -- the same wall `tools/node` hits with nodejs.org's builds. Termux's
# `mono` is built against Bionic, so `bin/mono` is an ordinary Android ELF whose
# interpreter is `/system/bin/linker64`, which spike R9 established this app can
# start.
#
# It is ~9 MB installed, against ~120 MB for Node, and carries both halves of
# what the milestone asks for: `mcs` compiles C# and `mono` runs the assembly.
#
#   ./fetch-mono.sh <staging-dir> [arch]        arch: aarch64 | x86_64
#
# Produces <staging>/mono.tar, a Termux `usr` tree.
set -euo pipefail

STAGING="${1:?usage: fetch-jvm.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o Packages

# One root. Mono carries its own compiler and runtime in the same package.
echo "==> dependency closure"
python3 - mono << 'PYTHON_EOF'
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
[ -x "$PREFIX/bin/mono" ] || { echo "no mono runtime in the closure" >&2; exit 1; }
[ -f "$PREFIX/bin/mcs" ] || { echo "no mcs compiler in the closure" >&2; exit 1; }

# **Trimmed, and only of what is genuinely redundant.** The closure unpacks to
# 227 MB, most of it assemblies nobody here compiles against: `lib/mono/*-api`
# holds reference assemblies for targeting older frameworks, 109 MB across a
# dozen profiles, and none of it is used to build or run against 4.5.
#
# `lib/mono/gac` looks like the same kind of duplication and **is not**.
# `lib/mono/4.5` is largely a farm of symlinks *into* the GAC, so removing it
# leaves the profile full of dangling links and mcs dies with
# `Could not load file or assembly 'System.Core'` -- which reads as a missing
# dependency rather than a deleted one. That was tried; it is why this list is
# as short as it is.
#
# 227 MB -> 117 MB, with `:toolchain:native`'s tests still compiling and running
# a console app. `tools/mono/FINDINGS.md`.
echo "==> trimming"
rm -rf "$PREFIX"/lib/mono/*-api "$PREFIX/include" "$PREFIX/share" "$PREFIX/var"

echo "==> mono.tar"
tar cf mono.tar -C "$PREFIX" .

echo
echo "mono.tar: $(du -h mono.tar | cut -f1)   installed: $(du -sh "$PREFIX" | cut -f1)"
