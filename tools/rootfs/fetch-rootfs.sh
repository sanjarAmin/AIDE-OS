#!/usr/bin/env bash
#
# Assembles what spike R11 needs to answer risk R4: does PRoot still work on a
# current Android?
#
# M9 (the Gradle path) rests on running a real Linux userland on the device --
# Gradle needs a JVM, and ART is not one. PRoot is how that is done without
# root, and `docs/PLAN.md` records it as a medium risk because Android 15's
# seccomp changes were reported to break it. That question is worth answering
# before anything is built on top, exactly as R9 and R10 were answered before
# M7.
#
# Produces <staging>/rootfs.tar containing:
#   proot/    PRoot and the two libraries it needs, from Termux
#   alpine/   a minimal Alpine userland, ~3 MB, to be the guest
#
# Alpine rather than Debian: it is the smallest thing that is still a real
# distribution with a package manager, and this spike is about whether the
# mechanism works at all, not about which distribution to ship.
#
#   ./fetch-rootfs.sh <staging-dir> [arch]        arch: aarch64 | x86_64
set -euo pipefail

STAGING="${1:?usage: fetch-rootfs.sh <staging-dir> [arch]}"
ARCH="${2:-aarch64}"
TERMUX="https://packages.termux.dev/apt/termux-main"
ALPINE_VERSION="3.24.1"
ALPINE="https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$ARCH"

# proot itself, plus what it links against. libandroid-shmem matters: PRoot
# uses System V shared memory, which Bionic does not implement, and Termux
# supplies it as a shim.
PACKAGES=(proot libtalloc libandroid-shmem)

mkdir -p "$STAGING"
cd "$STAGING"

echo "==> package index"
curl -fsSL "$TERMUX/dists/stable/main/binary-$ARCH/Packages" -o Packages

echo "==> proot and its libraries"
rm -rf proot && mkdir -p proot
for package in "${PACKAGES[@]}"; do
  read -r path sha < <(
    python3 - "$package" << 'PYTHON_EOF'
import re, sys
name = sys.argv[1]
for block in open("Packages", encoding="utf-8").read().split("\n\n"):
    if re.search(rf"^Package: {re.escape(name)}$", block, re.M):
        path = re.search(r"^Filename: (.+)$", block, re.M)
        sha = re.search(r"^SHA256: (.+)$", block, re.M)
        if path and sha:
            print(path.group(1), sha.group(1))
        break
PYTHON_EOF
  )
  [ -n "${path:-}" ] || { echo "$package is not in the index" >&2; exit 1; }

  file="$(basename "$path")"
  [ -f "$file" ] || curl -fsSL "$TERMUX/$path" -o "$file"
  echo "$sha  $file" | sha256sum -c --quiet
  (cd proot && ar x "../$file" data.tar.xz && tar xf data.tar.xz && rm data.tar.xz)
done

# Termux unpacks to its own absolute prefix; drop it, as the clang toolchain
# does. Everything inside is relocatable given LD_LIBRARY_PATH.
PREFIX=proot/data/data/com.termux/files/usr
[ -d "$PREFIX" ] || { echo "unexpected package layout" >&2; exit 1; }
rm -rf proot-flat && mkdir proot-flat && cp -a "$PREFIX/." proot-flat/
rm -rf proot && mv proot-flat proot

echo "==> alpine minirootfs $ALPINE_VERSION"
ROOTFS="alpine-minirootfs-$ALPINE_VERSION-$ARCH.tar.gz"
[ -f "$ROOTFS" ] || curl -fsSL "$ALPINE/$ROOTFS" -o "$ROOTFS"
rm -rf alpine && mkdir alpine
# No --same-owner: everything ends up owned by whoever unpacks it, which on a
# device is the app. PRoot fakes root inside the guest anyway.
tar xf "$ROOTFS" -C alpine --no-same-owner --no-same-permissions 2>/dev/null || \
  tar xf "$ROOTFS" -C alpine

echo "==> rootfs.tar"
tar cf rootfs.tar proot alpine

echo
echo "rootfs.tar: $(du -h rootfs.tar | cut -f1)"
echo "proot:      $(du -sh proot | cut -f1)"
echo "alpine:     $(du -sh alpine | cut -f1)"
