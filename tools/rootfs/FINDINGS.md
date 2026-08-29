# Spike R11: the Gradle path's foundation — result

**Outcome: the planned route is closed, and a better one is open but not yet
proven.** M9 assumed a Linux rootfs entered with PRoot, because Gradle needs a
JVM and ART is not one. That does not work — but the premise was wrong anyway,
and the thing the rootfs existed to provide can be had directly.

Evidence is `spike/rootfs`, five instrumented tests, on **Android 16 / API 36
arm64**.

## 1. Risk R4 is not what it says

`docs/PLAN.md` carries R4 as "PRoot broken on Android 15+ seccomp". **PRoot is
not broken.** It starts, `ptrace`s its child, rewrites paths and reports its own
diagnostics. The tracing machinery works.

What fails is the guest:

```
proot error: execve("/bin/busybox"): No such file or directory
```

Alpine is musl-linked and its ELF interpreter is `/lib/ld-musl-aarch64.so.1`.
An app targeting a modern SDK may not `execve` anything out of its own storage
(spike R9), and the single route it has — `/system/bin/linker64` — is Bionic's
loader, which cannot host a musl program. Handed the binary directly it gets as
far as looking for `libc.musl-aarch64.so.1`; pointed at musl's own loader it
segfaults.

**PRoot rewrites paths. It does not grant permission to execute, and it cannot
supply a second libc.** No amount of PRoot fixes either.

That Termux does run `proot-distro` successfully is not a contradiction: Termux
executes binaries from its own app storage, which is the permission this app
does not have.

## 2. The rootfs was never the point

The rootfs existed to provide a JVM. **Termux publishes OpenJDK built against
Bionic** — `bin/java` is an ordinary Android ELF, `interpreter
/system/bin/linker64`, built with NDK r29 for Android 24. That is exactly the
shape R9 established can be started and R10 has been running clang as ever
since. No guest, no PRoot, no second libc. Termux also publishes Gradle 9.7.1.

| | |
|---|---|
| `openjdk-21` closure | 36 packages, 111 MB download |
| Installed | 303 MB |
| JDK | `lib/jvm/java-21-openjdk` |

Reproduce with `fetch-jvm.sh`.

## 3. It runs under `run-as` and **not** in the app

Under `adb shell run-as`:

```
openjdk version "21.0.12" 2026-07-21
OpenJDK 64-Bit Server VM (build 21.0.12, mixed mode)
```

`mixed mode` means the JIT is live — the VM maps pages and then executes them,
which is R9's `PROT_EXEC` finding carrying a 300 MB runtime.

From the app's own process, with a **byte-for-byte identical environment**
(dumped by `env(1)` from that process to be sure), the same command dies:

```
error: expected absolute path: "-version"
```

That message is the linker's. OpenJDK's launcher re-execs itself through
`/proc/self/exe`, which under this launch is `/system/bin/linker64`, so the
re-exec becomes `linker64 -version`. Run with no arguments the evidence is
plainer: back comes the *linker's* usage text wearing java's path as `argv[0]`.

The launcher only re-execs when it wants to change something about its own
process. The environment being identical, whatever it wants differs between
`runas_app` and `untrusted_app`; the primordial thread's stack is the obvious
candidate and **is not established here**.

**`run-as` is not evidence.** This is `tools/clang/FINDINGS.md` §7 again, and it
nearly cost more this time: the permissive domain gave the answer M9 wanted, and
building on it would have produced a milestone the app cannot run.

`LD_LIBRARY_PATH` must contain `<jdk>/lib/server:<jdk>/lib:<usr>/lib`. The third
is not optional — without it `libjli.so` cannot find `libz.so.1`, which is a
Termux package rather than part of the JDK.

## 4. What M9 should do instead

**Replace the launcher.** `bin/java` is a thin wrapper around
`JNI_CreateJavaVM` in `libjvm.so`, and its re-exec is a convenience for fixing
its own environment. A launcher of our own does not re-exec, and M7 shipped a C
compiler that can build one. That is the next spike, and it is small.

Failing that, two fallbacks worth naming rather than discovering later:

- **Load `libjvm.so` into the app process** with `JNI_CreateJavaVM` directly.
  No second process at all. A second VM beside ART in one process is unusual
  and would need care about signals and `SIGSEGV` handlers, which both runtimes
  install.
- **Lower `targetSdk` to 28**, which is what Termux does and the whole reason it
  can execute its own files. That reopens the rootfs route and PRoot with it,
  at the cost of the platform's current-target requirements. `docs/PLAN.md`
  already makes F-Droid the primary channel, so this is less costly here than
  it would be elsewhere — but it is a large decision and belongs to the project
  owner, not to a spike.

## 5. Whether the JVM can fork is still open

Gradle's normal mode is a long-lived daemon it forks. A child would have to
`execve` out of app storage, which is forbidden — so `--no-daemon` is the
likely shape. Untested, because the VM does not yet start in the app.

## Open

- Does a hand-written launcher avoid the re-exec? (The next spike.)
- Why does the launcher decide differently under the two domains? The stack-size
  hypothesis is untested.
- Gradle itself: 164 MB installed, entirely unexercised here.
- x86_64 is untested; only arm64 was measured. The mechanism is
  architecture-independent but the claim is not.
