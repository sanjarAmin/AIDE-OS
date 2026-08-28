# Spike R10: clang on the device — result

**Outcome: resolved, with two rules that shape M7.** Termux's clang 21.1.8 runs
unmodified on Android from app-private storage, compiles C and C++ for the
device, and the shared library it produces loads into the app and executes. Reproduce the
toolchain with `fetch-toolchain.sh`; the evidence is `spike/clang`, eight
instrumented tests.

The two rules, both about what clang cannot do rather than what it can:

1. **One job per invocation.** `clang -shared foo.c -o foo.so` fails.
2. **clang never runs the linker.** Even a link-only invocation fails. The
   driver has to *plan* the link and something else has to execute it.

Neither is a defect in the toolchain. Both follow from spike R9's finding that
a downloaded binary can only be started through `/system/bin/linker64`, and
both were named there as open questions.

## What was measured

| Property | Value |
|---|---|
| clang | 21.1.8, `x86_64-unknown-linux-android24` (and aarch64) |
| Download | 100 MB, 16 `.deb` packages |
| `toolchain.tar` | 549 MB |
| Installed | 600 MB, ~900 symlinks |
| Unpack on device | 3.1 s |
| Compile a JNI `.c` to `.o` | 50–180 ms |
| Compile the same in C++ (`<string>`, `<fstream>`) | ~1,050 ms |
| Link a `.so` | 30–130 ms |
| Library produced | 5,368 bytes, loads and runs |

The timings are a range across runs on an x86_64 emulator. The first
invocation after an install pays for faulting in 139 MB of `libLLVM.so`, and
later ones are two to three times faster. Neither figure is a phone.

`clang-21` itself is 126 KB. Essentially all of it is `libLLVM.so`, at 139 MB —
which is why there is no meaningfully smaller version of this to ship.

## Termux, not the NDK

NDK clang is built for glibc hosts and cannot start on Android at all. Termux
builds the same LLVM for Bionic, as PIE binaries the platform linker can run,
against a sysroot that targets Android. That is the entire reason for the
choice, and it is not a close call — there is no other packaged clang that runs
on a stock device.

The packages come from `packages.termux.dev`, index at
`dists/stable/main/binary-$ARCH/Packages`, and `fetch-toolchain.sh` walks
`Depends:` from six roots (`clang lld libllvm libcompiler-rt ndk-sysroot
libc++`) to a 16-package closure. Walking it matters: a hand-picked list of 15
was missing `libandroid-glob`.

Termux packages unpack to `/data/data/com.termux/files/usr`, which we do not
have. Everything inside is relocatable — `LD_LIBRARY_PATH`, `--sysroot` and
`-resource-dir` cover all of it — so the prefix is simply dropped. The
binaries' own `RUNPATH` still points at Termux's prefix, so `LD_LIBRARY_PATH`
is not optional.

## 1. Launched through the linker, clang cannot locate itself

clang finds its resource directory — its builtin headers, and where it looks
for its own tools — relative to `/proc/self/exe`. Started as
`linker64 .../clang-21`, that path is *the linker's*:

```
InstalledDir: /apex/com.android.runtime/bin
$ clang-21 -print-resource-dir
/apex/com.android.runtime/lib/clang/21      # does not exist
```

The symptom is misleading. A trivial file compiles fine, because it needs no
builtin headers; anything including `<stdio.h>` fails three headers deep on
`asm/types.h`, which reads like a broken sysroot rather than a broken launch.
`spike/clang` asserts the wrong resource dir directly, so a future toolchain
that fixes this is noticed rather than silently double-handled.

**Four flags undo it, and every invocation needs all four:**

```
-resource-dir <toolchain>/lib/clang/21    # replaces what /proc/self/exe gave
-B            <toolchain>/bin             # where its own tools live; without
                                          # this, -fuse-ld=lld fails with
                                          # "invalid linker name"
--sysroot=    <toolchain>
-I            <toolchain>/include/<triple>
-cxx-isystem  <toolchain>/include/c++/v1     # C++ only
```

The last two are not about the launch at all, and they share a cause: for
Android targets clang derives both paths from a sysroot laid out as
`<root>/usr/include`, and Termux's prefix has no `usr/` level. So the
architecture-specific headers — `asm/` above all, which live in a per-triple
directory — and the libc++ headers both go unfound while sitting two
directories away. `#include <stdio.h>` fails three headers deep on
`asm/types.h`; `#include <string>` fails with `'string' file not found`.

`-cxx-isystem` rather than `-I` for the C++ one, so libc++ counts as a system
header directory: warnings from inside the standard library are not the user's
problem, and `-I` reports them as if they were.

`:toolchain:native` should add these centrally. Leaving each caller to remember
produces a missing-header error that looks like a different problem entirely.

## 2. One job per invocation

`clang -shared foo.c -o foo.so` is two jobs. clang runs the compile in-process
when it is the only job; with a link to follow it spawns a separate `cc1` —
through `/proc/self/exe`, which is the linker:

```
"/apex/com.android.runtime/bin/linker64" -cc1 -triple x86_64-…
error: expected absolute path: "-cc1"
```

`-fintegrated-cc1` does **not** help; it was tried. Splitting the work so each
invocation has exactly one job works, and that is what `:engine:fast`'s native
stage has to do.

## 3. clang can never run the linker — plan with the driver, execute ourselves

Reduced to a single job — link an existing `.o`, no compiling — the driver
still fails, and for a different reason:

```
clang-21: error: unable to execute command: Program could not be executed
clang-21: error: linker command failed due to signal (use -v to see invocation)
```

Linking is not something clang does in-process. It always `execve`s `ld.lld`,
and `ld.lld` is in app-private storage, which R9 established is never
executable. No flag fixes this: `-B` already points at the directory, clang
finds `ld.lld` perfectly well, it just cannot start it. The error names neither
the file nor the permission and reads like a crash.

**The way through is `-###`.** It prints the driver's plan and executes
nothing, so it survives the restriction. The last line is the complete `ld.lld`
invocation — 29 arguments here: every `crtbegin_so.o`,
`libclang_rt.builtins-*.a`, `-l:libunwind.a` and search path the platform
needs, worked out by the driver. That command then runs through the same
`linker64` route that starts clang, which is exactly what the driver could not
do for itself.

Hand-writing the linker command is the alternative. It would be a copy of
clang's per-target logic and would go stale the first time the toolchain moves.

This generalises past linking: **any** tool clang wants to spawn has the same
problem and the same answer. `:engine:fast` should own it as a strategy —
plan with the driver, execute with the linker — not as a special case for
`ld.lld`.

## 4. The toolchain must be unpacked by the app itself

Extracting as root and fixing the owner afterwards does not work. Android
labels app-private files with per-app SELinux categories, and files created by
another domain never get them. `ls` as root shows a perfectly good tree; the
app then gets `Permission denied` on individual files, and the dynamic linker
reports it as:

```
library "libz.so.1" not found
```

which reads like a missing file rather than an unreadable one. Hours went into
that message.

A child process of the app inherits the app's domain, so running `tar` from
inside the app produces a tree the app can use. `:toolchain:manager` must
download and unpack in-process for the same reason. This is also why the spike
is driven with `adb shell am instrument` rather than Gradle: AGP uninstalls the
test APK between runs and wipes the 600 MB with it.

**`adb push` drops symlinks.** All 917 of them, silently. Transfer the `.tar`
and unpack on the device; never push the tree.

## 5. C++ works, and it adds a file the built APK must carry

Most NDK code is C++, so a native stage that only handled C would not be worth
shipping. It works: `std::string` and `<fstream>` compile, link and run,
1,055 ms for the compile against ~80 ms for the equivalent C — the libc++
headers are what that buys.

Two things come with it.

**The C++ driver is the same binary, chosen by `argv[0]`.** `clang++-21` is a
symlink to `clang-21`, and the basename it is invoked under is the whole of
what selects C++ mode — the standard, the header path, `-lc++_shared`. No flag
does this. That makes the symlink load-bearing, and a second reason the
toolchain is transferred as a tar: of the ~900 symlinks a zip or an `adb push`
flattens, this one decides what language gets compiled. It survives the
`linker64` launch because `argv[0]` is the path we pass, unlike
`/proc/self/exe`, which the linker does rewrite.

**`libc++_shared.so` is part of the toolchain, not part of Android.** The
driver plans `-lc++_shared` into every C++ link, and nothing on the device
resolves it. The spike loads it by hand from the toolchain to prove the library
runs; a real build cannot, and has to copy `libc++_shared.so` (1.3 MB) into the
APK it is building, beside the library that needs it — which is what the NDK's
own Gradle plugin does. That copy is a `:engine:fast` job and has no other
natural owner.

## What this means for M7

| Module | What it has to do |
|---|---|
| `:toolchain:manager` | Download the closure, verify, unpack **in-process** |
| `:toolchain:native` | Add the linker64 launch and the relocation flags centrally |
| `:engine:fast` | One job per invocation; plan-then-execute for the link; copy `libc++_shared.so` into the APK |

## Open

- **Size.** 600 MB installed is a lot to ask of a phone. 107 MB is the sysroot
  headers, 47 MB the clang resource dir, 139 MB `libLLVM.so`, and ~115 MB of
  `bin/` is the `llvm` package — `opt`, `llc` and friends, which a compile
  never touches but which came in through the dependency walk. Trimming looks
  possible and is untested; `llvm-ar` and `llvm-strip` are probably wanted for
  static libraries later, so it is not simply "drop the package".
- **arm64 — the artifacts check out, running them needs hardware.**
  `fetch-toolchain.sh <dir> aarch64` produces a 538 MB tree, and every piece
  the design depends on is right: `clang-21` and `ld.lld` are AArch64 ELF64
  PIE with `/system/bin/linker64` as their interpreter — R9's exact route —
  alongside the aarch64 sysroot, the clang resource dir, the libc++ headers,
  `libclang_rt.builtins-aarch64-android.a`, and the
  `clang++-21 → clang++ → clang-21` symlink chain intact through the tar.

  What is unverified is that they *run*, and that cannot be done here. **The
  Android emulator refuses arm64 system images on an x86_64 host** — `Avd's CPU
  Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host`
  — despite shipping a `qemu-system-aarch64` binary, which makes the tree
  misleading. So this needs a real arm64 device on adb; there is no software
  route to it from this machine.

  The residual risk is low and worth stating precisely: what is untested is
  Android's behaviour (the exec route, W^X, SELinux categories), not the
  architecture, and none of those findings are CPU-dependent. Nothing here
  suggests arm64 differs — it simply has not been run.
- **Parallelism.** One job at a time is a correctness rule per *invocation*, not
  a ban on running several invocations concurrently. Untested.
