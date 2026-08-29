# Spike R11: the Gradle path's foundation — result

**Outcome: the planned route is closed, and a better one works.** A real JVM
runs on the device with no Linux userland at all — it compiles a class with
`javac` and runs it — reached by replacing OpenJDK's launcher rather than
arguing with it. M9 assumed a Linux rootfs entered with PRoot, because Gradle needs a
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

## 4. Replacing the launcher fixes it

`bin/java` is a wrapper around `JNI_CreateJavaVM` in `libjvm.so`, and its
re-exec is a convenience for fixing its own environment. A launcher that calls
that entry point directly has nothing to fix and never re-execs.
`spike/rootfs/src/main/jni/launch_jvm.c` is ~120 lines and does exactly that:
`dlopen` the VM, `JNI_CreateJavaVM`, `FindClass`, call `main`.

**It ships in `jniLibs`, so it needs no linker trick at all.** That directory is
extracted to `nativeLibraryDir`, which is the one place an app may execute from
— the same route `:toolchain:native` ships aapt2 by. `dlopen` of `libjvm.so`
out of app storage is permitted; only `execve` is not.

Measured in the app's own process, API 34 x86_64:

| | |
|---|---|
| `JNI_CreateJavaVM` | returns 0 |
| `javac` on a one-class file | ~400 ms |
| Running the compiled class | ~165 ms |
| Reported | `21.0.12 on amd64` |

Two things cost an hour each and are worth writing down.

**`-Djava.home` is not optional.** The VM otherwise derives it from the
launcher's own path, and the launcher is in `nativeLibraryDir`, nowhere near the
JDK. Without it the VM cannot find its own modules.

**A shared library with `-Wl,-e,main` is not an executable.** `ndk-build`
refuses an extension in an executable's module name, and the obvious way around
that — build a shared library and give it an entry point — produces a file that
starts and then segfaults *before reaching `main`*, with no output at all. A
shared object is linked without the C runtime startup, so libc and TLS are never
initialised and the first `fprintf` dies. It has to be a real executable, merely
*named* `lib….so`. The build compiles it with the NDK's clang directly for that
reason.

**The NDK's `jni.h` is Android's**, and stops at JNI 1.6. OpenJDK's VM wants at
least 1.8 in `JavaVMInitArgs.version`, so the constant is defined in the
launcher rather than included. The header at hand and the runtime being driven
are not the same implementation.

## 5. The VM can fork, but only if told how

Gradle's normal mode is a daemon it forks. The default answer looks like "no":

```
java.io.IOException: Cannot run program "/system/bin/echo":
  Failed to exec spawn helper
```

That is not the platform refusing to spawn. The JVM's default launch mechanism
on Linux is `POSIX_SPAWN`, which runs a small JDK binary called `jspawnhelper`
and has *it* exec the target — and `jspawnhelper` is in app-private storage,
which is what may not be executed. The failure is about the helper.

`-Djdk.lang.Process.launchMechanism=vfork` skips it and execs the target
directly. `/system/bin/echo` is in an executable location, so it runs:
`FORK-OK 0 child-ran`.

**So the constraint is what gets exec'd, not that anything is.** A child under
`/system/bin` is fine; a child in app storage is not — which means a Gradle
daemon, being another `java`, still cannot be spawned the way Gradle spawns it.
It would have to be started through this launcher, or Gradle run with
`--no-daemon`.

## 6. Gradle runs; a build still needs a daemon it cannot fork

Gradle 9.7.1 starts on the launcher and reports itself:

```
Gradle 9.7.1
Launcher JVM:  21.0.12 (Termux 21.0.12)
OS:            Linux ... amd64
```

**Under `run-as` it builds.** A `java-library` project compiles and
`demo.jar` appears with `demo/Greeter.class` in it, in about ten seconds.

**In the app's own process it does not**, and the reason is the one this whole
spike keeps meeting:

```
To honour the JVM settings for this build a single-use Daemon process
will be forked.
A problem occurred starting process 'Gradle build daemon'
```

The daemon is another `java`, in app-private storage, which may not be
executed. `run-as` may — the third time in this spike that domain has answered
a question more favourably than the app can, and the reason the `run-as` result
above is reported but not relied on.

`--no-daemon` does not prevent it. Gradle still forks a *single-use* daemon
whenever it decides the client JVM does not match what the build wants, and
passing matching `-Xmx` and `-XX:MaxMetaspaceSize` did not change that. Gradle
also reports `There is no native integration with this operating environment` —
its native-platform library has no Android support. The two are plausibly
connected and that is **not established**.

Two smaller things a build needs, both found the hard way:

- **`android.permission.INTERNET`.** Gradle's `FileLockContentionHandler` binds
  a socket for inter-process lock coordination, and an Android app cannot make
  a socket without that permission. The failure names neither: *"Could not
  determine a usable wildcard IP for this machine."*
- **`-Djava.io.tmpdir`.** The Termux JDK bakes in Termux's own prefix, which
  does not exist here, and Gradle dies deep inside service construction:
  *"java.io.tmpdir is set to a directory that doesn't exist:
  /data/data/com.termux/files/usr/tmp"*.

### The route out, not yet tried

Gradle spawns the daemon by exec'ing `$java.home/bin/java`. **A symlink at that
path pointing into `nativeLibraryDir` would be exec'd legally** — the kernel
checks the resolved file, and that directory is executable. What it would reach
is our launcher, which does not take `java`'s arguments.

So the step after this is to make the launcher argument-compatible with `java`:
parse `-cp`, `-D`, `-X`, then a main class. That is worth doing beyond Gradle —
anything that shells out to `java` starts working — and it is a contained piece
of C.

## What this means for M9

No rootfs, no PRoot, no second libc. A JDK installed the way the C/C++
toolchain is, plus a launcher of ours in `jniLibs`, and the JVM runs. The
remaining work is Gradle on top of it.

| Module | What it has to do |
|---|---|
| `:toolchain:manager` | Install the JDK — the gzipped-tar path already exists |
| `:toolchain:native` | Ship the launcher in `jniLibs`, beside aapt2 |
| A Gradle bridge | Drive Gradle through the launcher. The daemon is the open problem: see §6 |

## Open

- **Gradle builds under `run-as` and not in the app.** §6. The next step is a
  `java`-compatible launcher plus a symlink, which would let Gradle fork its
  daemon through a path it is allowed to execute.
- **Whether Gradle can be made not to fork at all** is unresolved; matching the
  JVM settings did not do it.
- **Why the stock launcher decides differently under `run-as`** is still not
  established. It no longer blocks anything, but it is unexplained.
- **The launcher was measured on x86_64.** It builds for both ABIs and the
  arm64 JDK was exercised through the stock launcher under `run-as`, but the
  launcher itself has not run on arm64 — the phone was unplugged before that
  test existed.
- **Memory.** A JVM plus Gradle plus the C/C++ toolchain on a phone is a lot of
  resident set, and nothing here measured it.
