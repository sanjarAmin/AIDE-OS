#!/usr/bin/env bash
#
# Builds aapt2 for Android/arm64-v8a -- the one native binary the fast build
# path needs. Everything else in that pipeline (ECJ, kotlinc, D8, apksig) is
# pure JVM and runs on ART directly.
#
# The result is a PIE executable linked only against Bionic's libc/libm/libdl/
# libz, with libc++ linked statically. That matters: it can be shipped inside
# jniLibs as libaapt2.so and executed from nativeLibraryDir, the only location
# Android's W^X policy has permitted since API 29.
#
# Requires: git go bison flex python3 cmake ninja + an Android NDK.
# Produces roughly 6 GB of AOSP sources, so do not run this on tmpfs.

set -euo pipefail

NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
WORK="${WORK:-$HOME/aide-os-spikes/aapt2}"

# AOSP tag to build from. Must match the patches under android-sdk-tools/patches,
# and pins the aapt2 feature set the fast path targets.
AOSP_TAG="${AOSP_TAG:-platform-tools-35.0.2}"

# API 30 is a hard floor, not a preference: AOSP's libbase calls
# __android_log_set_logger / __android_log_set_default_tag (API 30) and
# android_fdsan_* (API 29) unconditionally. Builds at 26 and 29 both fail.
# The app itself stays at minSdk 26 -- the editor works everywhere and the
# build engine is gated at runtime on Build.VERSION.SDK_INT >= 30.
API="${API:-30}"

# protoc must generate C++ that AOSP's bundled protobuf runtime (3.21.12) can
# compile. A newer protoc emits code the older runtime rejects, so pin it.
# The prebuilt is used deliberately: AOSP's protobuf fork cannot build on a
# modern host toolchain (its common.cc includes an autotools-only config.h).
PROTOC_VERSION="${PROTOC_VERSION:-21.12}"

REPO="$WORK/android-sdk-tools"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }

step "Checking prerequisites"
for c in git go bison flex python3 cmake ninja curl unzip patch; do
    command -v "$c" >/dev/null || { echo "missing: $c" >&2; exit 1; }
done
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "no NDK toolchain file under $NDK" >&2; exit 1; }

step "Fetching build scripts"
mkdir -p "$WORK"
[ -d "$REPO" ] || git clone --depth 1 https://github.com/lzhiyong/android-sdk-tools.git "$REPO"

step "Preparing python environment"
# get_source.py imports requests at module scope even though the clone path
# never calls it.
[ -d "$REPO/.venv" ] || python3 -m venv "$REPO/.venv"
"$REPO/.venv/bin/pip" -q install requests

step "Cloning AOSP sources at $AOSP_TAG (~6 GB, slow)"
(cd "$REPO" && [ -d src/base ] || "$REPO/.venv/bin/python" get_source.py --tags "$AOSP_TAG")

step "Applying patches"
# get_source.py copies a few files and runs some seds, but never applies the
# .patch files. Without this one, libprotobuf cannot find AOSP's android/config.h.
(cd "$REPO" && patch -p1 --forward --silent < patches/protobuf_CMakeLists.txt.patch || true)
grep -q 'protobuf_SOURCE_DIR}/config' "$REPO/src/protobuf/CMakeLists.txt" \
    || { echo "protobuf patch did not apply" >&2; exit 1; }

step "Fetching protoc $PROTOC_VERSION"
if [ ! -x "$WORK/protoc/bin/protoc" ]; then
    mkdir -p "$WORK/protoc"
    curl -sL -o "$WORK/protoc/protoc.zip" \
        "https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip"
    (cd "$WORK/protoc" && unzip -q -o protoc.zip && chmod +x bin/protoc)
fi
"$WORK/protoc/bin/protoc" --version

step "Building aapt2 (arm64-v8a, API $API)"
# CMake 4 refuses cmake_minimum_required(<3.5), which several vendored AOSP
# CMakeLists still declare.
export CMAKE_POLICY_VERSION_MINIMUM=3.5
(cd "$REPO" && "$REPO/.venv/bin/python" build.py \
    --ndk="$NDK" \
    --abi=arm64-v8a \
    --api="$API" \
    --build="build/aarch64-api$API" \
    --protoc="$WORK/protoc/bin/protoc" \
    --target=aapt2)

OUT="$REPO/build/aarch64-api$API/bin/build-tools/aapt2"
[ -f "$OUT" ] || { echo "aapt2 not produced at $OUT" >&2; exit 1; }

step "Verifying the binary"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$READELF" -h "$OUT" | grep -E 'Class|Machine|Type'
"$READELF" -d "$OUT" | grep NEEDED

# A non-PIE executable will not load on modern Android; fail loudly rather than
# shipping something that only breaks on device.
"$READELF" -h "$OUT" | grep -q 'Type:.*DYN' \
    || { echo "FAIL: aapt2 is not PIE" >&2; exit 1; }

# Anything beyond these four means an extra .so has to ship alongside it.
if "$READELF" -d "$OUT" | grep NEEDED | grep -qvE 'lib(c|m|dl|z)\.so'; then
    echo "WARN: unexpected shared library dependency -- check jniLibs packaging" >&2
fi

printf '\n\033[1;32maapt2 ready: %s\033[0m\n' "$OUT"
echo "Package it into the app as jniLibs/arm64-v8a/libaapt2.so"
