#!/usr/bin/env bash
#
# Fetches llama.cpp built for Android, for spike R16 (a local model).
#
# The route the JDK, clang and Node already took: Termux builds against
# **Bionic**, so `bin/llama-server` is an ordinary Android ELF whose interpreter
# is `/system/bin/linker64` -- the one shape spike R9 established this app can
# start from its own storage. llama.cpp's own release binaries are glibc and
# cannot run here.
#
#   ./fetch-llama.sh <staging-dir> [arch]        arch: aarch64 | x86_64
#
# Produces <staging>/llama.tar, a Termux `usr` tree with the server at
# bin/llama-server. The model is not in it: a model is hundreds of megabytes to
# several gigabytes, and is the part a user chooses. See fetch-model.sh.
#
# **The CPU build only.** Termux also ships llama-cpp-backend-vulkan and
# -opencl. Whether a phone's GPU driver runs them is its own question, asked
# after the CPU numbers say whether a local model is worth having at all.
set -euo pipefail

STAGING="${1:?usage: fetch-llama.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o Packages

# One root. The closure -- libc++, libcurl and what curl needs, and
# libandroid-spawn -- is walked from it and named nowhere.
echo "==> dependency closure"
python3 - llama-cpp << 'PYTHON_EOF'
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
[ -x "$PREFIX/bin/llama-server" ] || { echo "no llama-server in the closure" >&2; exit 1; }

# **Trimmed to the server.** Headers, man pages and the pkg-config and cmake
# files are for building against libllama. The other thirty-odd tools --
# llama-cli, -bench, -quantize and the rest -- each come with an `-impl` library
# beside the server's, and nothing here runs them.
echo "==> trimming"
rm -rf "$PREFIX/include" "$PREFIX/share" "$PREFIX/lib/pkgconfig" "$PREFIX/lib/cmake"
find "$PREFIX/bin" -mindepth 1 ! -name llama-server -delete
find "$PREFIX/lib" -name 'libllama-*-impl.so' ! -name 'libllama-server-impl.so' -delete

echo "==> llama.tar"
tar cf llama.tar -C "$PREFIX" .

# **Gzipped for the download**, as clang's and Node's are: the manager unpacks
# a gzipped tar, and on a phone the difference is what the user waits for.
# The plain tar stays beside it for the spike, which unpacks with toybox.
gzip -9 -k -f llama.tar

echo
echo "llama.tar: $(du -h llama.tar | cut -f1)   llama.tar.gz: $(du -h llama.tar.gz | cut -f1)   installed: $(du -sh "$PREFIX" | cut -f1)"
echo "binaries: $(ls "$PREFIX/bin" | tr '\n' ' ')"
echo "sha1 of llama.tar.gz: $(sha1sum llama.tar.gz | cut -d' ' -f1)   bytes: $(stat -c %s llama.tar.gz)"
