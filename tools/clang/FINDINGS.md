# Spike R10: clang on the device — result

**Outcome: resolved, with two rules that shape M7.** Termux's clang 21.1.8 runs
unmodified on Android from app-private storage, compiles C and C++ for the
device, and the shared library it produces loads into the app and executes. Reproduce the
toolchain with `fetch-toolchain.sh`; the evidence is `spike/clang`, eight
instrumented tests, green on **API 34 x86_64 and API 36 arm64**.

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
| clang | 21.1.8, for `x86_64` and `aarch64-linux-android` |
| Verified on | API 34 x86_64 emulator; **Android 16 / API 36 arm64** (nubia NX809J) |
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

If you try to reproduce this failure by hand and it *succeeds*, read §7 before concluding anything: `run-as` is allowed to do what the app is not.

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

## 6. It holds on Android 16 / arm64, unchanged

The one platform question R10 could not answer from an emulator. Run on a
nubia NX809J (Red Magic 11S Pro): **Android 16, API 36, arm64-v8a, SELinux
enforcing**, two API levels above anything else tested here and an OEM ROM
rather than an AOSP image.

All eight tests pass, and — this is the part that matters — they pass
*including the two that assert failure*. clang still cannot locate itself,
still cannot run the linker. The model is not merely "still working"; it is
identical.

| | API 34 x86_64 | API 36 arm64 |
|---|---|---|
| Compile a JNI `.c` | 50–180 ms | 87 ms warm, 125 ms cold |
| Plan a link (`-###`) | ~50 ms | 101 ms |
| Whole suite, warm | 2.3 s | 2.7 s |
| Installed size | 600 MB | 551 MB |

This is the direct answer to risk **R9** — "the `linker64` exec route closing in
a future Android". Two API levels on, on a vendor ROM, it has not closed.

## 7. `adb shell run-as` is not the app, and will contradict these findings

**Do not verify any of this by hand through `run-as`.** It runs in a different
SELinux domain — `runas_app`, not `untrusted_app` — and that domain is allowed
to `execve` out of app-private storage. So the probe that §3 says must fail:

```
$ adb shell run-as <pkg> sh -c '... clang-21 ... -shared b.o -o direct.so'
rc=0
-rwxrwxrwx  5304  direct.so
```

succeeds, on **both** the phone and the emulator, while the in-process test
asserting the same thing fails the same link on the same device seconds later.
Nothing about the platform differs; only who is asking.

This was found by re-checking §3 on the phone and briefly believing Android 16
had *relaxed* the restriction. It had not — `run-as` had simply never been
subject to it, and every manual probe during this spike had gone through
`run-as` without that being noticed.

The consequence is a rule, not a caveat: **only an instrumented test running in
the app's own process can answer a question about exec or SELinux here.**
`run-as` is still useful for looking at files, and it is what §4's diagnosis
rests on. It is worthless for deciding what the app may execute.

## 8. Distribution: one gzipped tar per ABI, on this repo's releases

Published as `clang-21.1.8`, the same way the Kotlin compiler is, and for the
same reason: nothing upstream ships a clang that runs on Android, so if this
project does not host one, there is nothing to point `:toolchain:manager` at.

| Asset | Download | Installed | SHA-1 |
|---|---|---|---|
| `clang-21.1.8-aarch64.tar.gz` | 152 MiB | 551 MB | `17ffea7d…` |
| `clang-21.1.8-x86_64.tar.gz` | 155 MiB | 600 MB | `008cc6f6…` |

**Gzipped, and that is not a detail.** The tar is 538 MB and compresses 3.5×;
the difference is between a download a phone finishes and one it does not.

**Tar, not zip, and this is not a preference.** A zip cannot carry symlinks, and
`clang++ -> clang` is the whole of what selects C++ mode. An installer that
flattened it would produce a toolchain that silently compiles C when asked for
C++ — not an error, just wrong output.

The alternative considered was fetching Termux's 16 `.deb` packages on the
device: better provenance, no re-hosting, but sixteen downloads instead of one
and `ar` plus `xz` handling on the device. Rejected for the download shape.
`fetch-toolchain.sh` still produces the archives, so the provenance is a script
away rather than lost.

## 9. clangd runs, and does not need the thing it cannot have

The remaining half of M7's deliverable, and it was not obviously possible.
Everything the compiler needed a workaround for applies again — started through
the linker, misled by `/proc/self/exe`, forbidden from executing any program —
and clangd makes it worse in two ways. It is a long-lived server rather than a
one-shot process, and **its usual way of discovering system headers is to run
the compiler and ask it** (`--query-driver`), which is exactly what this
platform refuses.

It works anyway, because `--query-driver` is off unless asked for. The paths go
in a `compile_flags.txt` beside the sources instead:

```
-xc++
-resource-dir=<toolchain>/lib/clang/21
--sysroot=<toolchain>
-I<toolchain>/include/<triple>
-isystem<toolchain>/include/c++/v1
```

The resource directory is the one that decides whether this works at all.
Without it clangd looks under `/apex/com.android.runtime/lib/clang/21`, finds
no `stddef.h`, and reports errors inside every system header — which reads as a
broken sysroot rather than a broken launch, the same misdirection §1 describes.

`compile_flags.txt` rather than `compile_commands.json`: every file in a project
is compiled with the same flags here, and clangd finds the simpler format by
walking up from the file itself.

**Measured:** `initialize` answered and a real diagnostic published in **0.47 s**
on an x86_64 emulator and **0.52 s** on Android 16 arm64 — `Expected ';' at end of declaration (fix available)`.
That the diagnostic exists at all is the proof: answering `initialize` needs no
headers, so a server that got that far and no further would look healthy and be
useless. 18.6 MB, already inside the toolchain, so it costs no extra download.

### Two ways clangd answers wrongly rather than not at all

Both were found by building the client, and neither reports an error.

**An empty `capabilities` object is not a neutral default.** A server tailors
its replies to what the client says it understands, so announcing nothing gets
clangd's most conservative output: completion labels with no signature, **no
`kind` on any item** — so every proposal draws the same icon — and hover as
legacy `MarkedString` instead of `MarkupContent`, which a client expecting the
latter reads as empty. It all looks like the server working badly rather than
the client having asked for less.

**A question asked before the parse finishes gets a confident wrong answer.**
With no AST yet, clangd answers completion from an identifier index: every item
comes back as `kind: 1` (Text) with a leading space in the label, so a method is
offered as a word that happens to appear in the file — listed beside `return`,
which is not a member of anything. The fix is to treat the first
`publishDiagnostics` after a change as the signal that an AST exists and wait
for it; an unchanged buffer needs neither the notification nor the wait.

**One convention to inherit.** clangd reports a missing semicolon at the token
that *revealed* it, not at the line missing it — the fault on line 2 is reported
at line 3. Whatever draws squiggles has to follow clangd's ranges rather than
correct them, or it will disagree by one line on the commonest mistake in C++.

## What this means for M7

| Module | What it has to do |
|---|---|
| `:toolchain:manager` | Download the closure, verify, unpack **in-process** |
| `:toolchain:native` | Add the linker64 launch and the relocation flags centrally |
| `:engine:fast` | One job per invocation; plan-then-execute for the link; copy `libc++_shared.so` into the APK |
| `:lsp:*` | clangd over stdio, fed a `compile_flags.txt`; never `--query-driver` |

## Open

- **Size.** 600 MB installed is a lot to ask of a phone, even at 155 MiB
  down. 107 MB is the sysroot
  headers, 47 MB the clang resource dir, 139 MB `libLLVM.so`, and ~115 MB of
  `bin/` is the `llvm` package — `opt`, `llc` and friends, which a compile
  never touches but which came in through the dependency walk. Trimming looks
  possible and is untested; `llvm-ar` and `llvm-strip` are probably wanted for
  static libraries later, so it is not simply "drop the package".
- **Parallelism.** One job at a time is a correctness rule per *invocation*, not
  a ban on running several invocations concurrently. Untested.
