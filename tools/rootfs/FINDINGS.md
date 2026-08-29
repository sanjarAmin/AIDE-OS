# Spike R11: the Gradle path's foundation — result

**Outcome: the planned route is closed, and a better one works.** A real JVM
runs on the device with no Linux userland at all; Gradle builds with it; and
**AGP builds an Android APK in the app's own process** — 33 tasks, aapt2 and D8
included, in 13 seconds. All of it reached by replacing OpenJDK's launcher
rather than arguing with it, and by symlinking the few binaries a build execs to
copies the app is allowed to run. M9 assumed a Linux rootfs entered with PRoot, because Gradle needs a
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

## 6. Gradle builds, once the launcher can stand in for `java`

Gradle 9.7.1 starts on the launcher and reports itself:

```
Gradle 9.7.1
Launcher JVM:  21.0.12 (Termux 21.0.12)
```

**A build needed one more thing.** Gradle starts its daemon by exec'ing
`$java.home/bin/java`, which is in app-private storage and may not be executed:

```
To honour the JVM settings for this build a single-use Daemon process
will be forked.
A problem occurred starting process 'Gradle build daemon'
```

`--no-daemon` does not prevent it — Gradle forks a *single-use* daemon whenever
it decides the client JVM does not match what the build wants, and passing the
matching `-Xmx` and `-XX:MaxMetaspaceSize` did not change that. (Gradle also
reports `There is no native integration with this operating environment`; the
two are plausibly connected and that is **not established**.)

**The fix is a symlink, and it needs no new permission.** `<jdk>/bin/java`
becomes a link to the launcher in `nativeLibraryDir`. The kernel checks the
*resolved* file against the no-execute rule, and that directory is executable —
so the exec Gradle always performs simply succeeds, and what runs is a launcher
that does not re-exec.

For that to work the launcher takes **`java`'s own arguments**: `-cp`, `-D…`,
`-X…`, `@argfile`, a main class, program arguments, and `-version`. `java.home`
comes from `JAVA_HOME` or, failing that, is derived from `argv[0]` exactly as
the real launcher does — which is what makes the symlink self-configuring,
since `argv[0]` is then the path inside the JDK rather than the launcher's own.
`-jar` is refused rather than half-implemented: running a jar means reading
`Main-Class` from its manifest, and silently running the wrong thing would be
worse than saying no.

**Measured in the app's own process**, API 34 x86_64: a `java-library` project
compiles and `demo.jar` contains `demo/Greeter.class`, in about ten seconds.
The test asserts the jar rather than the exit code, because a build that skipped
every task also exits zero.

Two smaller requirements, both found by hitting them:

- **`android.permission.INTERNET`.** Gradle's `FileLockContentionHandler` binds
  a socket for inter-process lock coordination, and an Android app cannot make
  a socket without that permission. The failure names neither: *"Could not
  determine a usable wildcard IP for this machine."*
- **`-Djava.io.tmpdir`.** The Termux JDK bakes in Termux's own prefix, which
  does not exist here, and Gradle dies deep inside service construction:
  *"java.io.tmpdir is set to a directory that doesn't exist:
  /data/data/com.termux/files/usr/tmp"*.

**A test that redirects `bin/java` has to put it back.** The first version left
the symlink in place, and a sibling test asserting that the *stock* launcher
re-execs then failed — it was looking at ours. Shared device state edited by one
test surfaces as a failure in another that never mentions it.

## 7. AGP builds an Android APK, in the app's own process

The whole pipeline, not just Gradle. AGP 9.3.2 resolves from Google's Maven,
applies, and runs 33 tasks — `processDebugResources` through aapt2,
`dexBuilderDebug` through D8, `packageDebug` — producing an **871 KB APK with a
binary `AndroidManifest.xml` and dex inside, in 13 seconds** — and, with an
AndroidX dependency and resources, a 3.2 MB one in 55.

Four substitutions, three of them the same idea: **a symlink from where a tool
is expected to a copy the app may execute**, because the kernel checks the
resolved file and `nativeLibraryDir` is executable.

| Expected at | Points to | Why |
|---|---|---|
| `<jdk>/bin/java`, `jlink`, `javac`… | our launcher | Gradle forks a daemon and AGP calls `jlink`; JDK binaries are in app storage |
| `<jdk>/lib/jspawnhelper` | a copy shipped in `jniLibs` | see below |
| `aapt2` | our `libaapt2.so` | AGP's own aapt2 is a **Linux x86_64 binary** and cannot run on Android at all |
| `sdk.dir` | a staged `platforms/`, `build-tools/`, licence | AGP will not build without them |

**`jspawnhelper` was the last blocker and the most instructive.** The JVM's
default `POSIX_SPAWN` runs that helper and has *it* exec the target. The JDK's
copy is in app-private storage, so every `ProcessBuilder` inside a build failed
with `Failed to exec spawn helper` — a message naming neither the helper's
location nor the reason.

`-Djdk.lang.Process.launchMechanism=vfork` avoids the helper and works for a JVM
we start ourselves (§5), but it has to reach *every* JVM a build starts, and
Gradle offers no reliable way to put it on the daemon's command line:
`org.gradle.jvmargs` does not carry a bare `-D` there, and `systemProp.` arrives
after `java.lang.ProcessImpl` has read the property. **Shipping the helper and
symlinking the JDK's copy at it fixes every JVM at once, including ones nobody
here launches** — a smaller change than the workaround it replaces, and one that
cannot drift. `spike/rootfs/src/main/prebuilt/README.md` records where the
binary came from.

### A realistic project, not just a toolchain check

The project above has no dependencies, no resources and no library manifests —
enough to prove the toolchain runs, not enough to prove AGP works. A second
project adds `androidx.appcompat`, an activity extending `AppCompatActivity`,
and a string resource, so the build must resolve a transitive graph from
Google's Maven, unpack the AARs, merge their manifests, link their resources
together with the project's, and compile against classes that exist only in
those AARs.

**It builds: a 3.2 MB APK, 423 entries, 55 seconds.** `resources.arsc` is the
assertion that matters — it is aapt2's output and cannot appear unless linking
really happened; `R.string.app_name` resolving at compile time is the other
half, since the generated `R` had to reach the compile classpath.

`android.useAndroidX=true` is required, and AGP refuses the build without it.

**Kotlin builds too**, at 3.2 MB and 53 seconds, with a `.kt` file that calls
into the Java half so the two compilers must see each other's output. It needs
**no Kotlin plugin**: AGP 9 has built-in Kotlin support and registers a `kotlin`
extension of its own, so applying `org.jetbrains.kotlin.android` on top fails
with *"Cannot add extension with name 'kotlin'"*. A `.kt` source is enough.

That also answers the question that made Kotlin worth trying: the Kotlin
compiler normally runs in a daemon of its own, a second JVM forked from inside
the Gradle daemon — a third level of process spawning, where each earlier level
had needed something fixed. Fixing `jspawnhelper` covered this one too, which is
the point of fixing it rather than passing a flag.

The launcher grew two things along the way. It **runs JDK tools by name**,
dispatching through `java.util.spi.ToolProvider` — the supported route, and the
only one available, since `jdk.tools.jlink.internal.Main` is not exported to the
unnamed module and `FindClass` cannot reach it. And it **fixes its own
`LD_LIBRARY_PATH` and restarts once**, because it cannot rely on the environment
it is handed: AGP execs `jlink` with its own. Re-exec is safe *here* for exactly
the reason it is fatal for the stock launcher — `/proc/self/exe` is this file, in
`nativeLibraryDir`, not the dynamic linker.

## What this means for M9

No rootfs, no PRoot, no second libc. A JDK installed the way the C/C++
toolchain is, plus a launcher of ours in `jniLibs`, and the JVM runs. The
remaining work is Gradle on top of it.

| Module | What it has to do |
|---|---|
| `:toolchain:manager` | Install the JDK — the gzipped-tar path already exists |
| `:toolchain:native` | Ship the launcher in `jniLibs`, beside aapt2 |
| A Gradle bridge | Drive Gradle through the launcher, with `<jdk>/bin/java` symlinked to it so the daemon fork works |
| An SDK | `platforms/`, `build-tools/`, a licence, and `aapt2` overridden to ours — AGP's own is a Linux binary |
| `:toolchain:native` | Ship `jspawnhelper` beside the launcher, and symlink the JDK's copy at it |

## Open

- **Whether Gradle can be made not to fork at all** is unresolved; matching the
  JVM settings did not do it. It no longer blocks anything, but a build that
  did not fork would be faster.
- **Compose is untried.** Kotlin works; Compose adds a compiler plugin, which is
  a different thing again.
- **`-jar` is not supported** by the launcher, and something will eventually
  want it.
- **Everything in §6 and §7 was measured on x86_64.** The launcher and
  `jspawnhelper` ship for both ABIs and the arm64 JDK ran under the stock
  launcher, but no arm64 device was attached when this was written.
- **The SDK is staged by hand.** `:toolchain:manager` would have to install a
  platform and build-tools the way it installs the others.
- **Why the stock launcher decides differently under `run-as`** is still not
  established. It no longer blocks anything, but it is unexplained.
- **The launcher was measured on x86_64.** It builds for both ABIs and the
  arm64 JDK was exercised through the stock launcher under `run-as`, but the
  launcher itself has not run on arm64 — the phone was unplugged before that
  test existed.
- **Memory.** A JVM plus Gradle plus the C/C++ toolchain on a phone is a lot of
  resident set, and nothing here measured it.
