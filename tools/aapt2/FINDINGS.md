# Spike R1: building aapt2 on-device — result

**Outcome: resolved.** aapt2 builds cleanly for Android/arm64-v8a and the
resulting binary is shippable inside the APK. Reproduce with `build-aapt2.sh`.

## What was produced

| Property | Value |
|---|---|
| Type | `DYN` — **PIE executable** |
| Machine | AArch64 |
| Interpreter | `/system/bin/linker64` (Bionic) |
| Dynamic deps | `libc.so`, `libm.so`, `libdl.so`, `libz.so` — nothing else |
| libc++ | linked statically (no `libc++_shared.so` to ship) |
| Size | 4.3 MB stripped |
| Build time | ~1m20s on 16 cores, after sources are cloned |

Only four Bionic system libraries are required, all present on every device,
so exactly one file ships: `jniLibs/arm64-v8a/libaapt2.so`.

## API 30 is a hard floor

The build was attempted at three API levels:

| API | Result |
|---|---|
| 26 | fails — `android_fdsan_*` (libbase `unique_fd.h`), introduced API 29 |
| 29 | fails — `__android_log_set_logger`, `__android_log_set_default_tag`, `__android_log_logd_logger` (libbase `logging.cpp`), introduced API 30 |
| 30 | **builds** |

AOSP's libbase calls these unconditionally, so this is a property of the AOSP
snapshot, not of our build configuration. Patching libbase to weak-link them is
possible but becomes a permanent maintenance burden against upstream.

**Consequence for the app:** the editor keeps `minSdk 26`, and `:build:fast`
is gated at runtime on `Build.VERSION.SDK_INT >= 30`. Shipping the `.so` on an
API 26 device is harmless — it is only ever executed, never loaded — but the
build action must be disabled there with a clear message rather than failing
at exec time.

## Building beats the prebuilt fallback

The plan listed AndroidIDE's prebuilt binary as the fallback. Their
`aapt2-arm64-v8a` (v34.0.4) is `Type: EXEC` — **not PIE** — and statically
linked, built with NDK r26-beta2. Android has required position-independent
executables since API 21, so it is a poor fit for exec-from-`nativeLibraryDir`.
Our own build is PIE and therefore the better artifact; the fallback should be
treated as a last resort, not the safe default.

## Two traps the upstream README does not cover

1. **`get_source.py` never applies the `.patch` files.** It only copies a few
   files and runs some seds. Without `patches/protobuf_CMakeLists.txt.patch`,
   libprotobuf fails with `config.h: No such file or directory` — AOSP's
   protobuf fork includes an autotools-generated header that its CMake path
   does not create.

2. **Do not build host protoc from AOSP's protobuf.** It hits the same
   `config.h` failure, and protobuf 3.21.12 (2022) does not compile under a
   modern host GCC. Use the upstream prebuilt `protoc` pinned to 3.21.12 to
   match the runtime AOSP bundles — a newer protoc emits code the older
   runtime rejects.

## Verified on device

Confirmed on an Android 14 x86_64 emulator by
`toolchain/native/src/androidTest/.../Aapt2InstrumentedTest.kt` (5 tests):

- the binary is packaged into `nativeLibraryDir` and is executable;
- it runs there under Android's W^X policy;
- it compiles a real `values/strings.xml` into a `.flat` resource;
- a malformed resource produces a non-zero exit and a diagnostic.

An x86_64 build was produced with the same script (`--abi=x86_64`) so the
pipeline can be tested without emulating a foreign architecture — despite the
upstream README saying only aarch64 had been tested, it built unmodified.

**aapt2 writes everything to stderr**, including `version` and success output;
stdout stays empty. Reading stdout to detect success silently passes on a
broken binary. `ToolResult.diagnostics` prefers stderr for this reason.
