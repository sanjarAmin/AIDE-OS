# `:engine:gradle` — what running Gradle on a phone costs

The second `BuildSystem`. `:engine:fast` is the default because it is fast and
needs no JVM; this exists for projects the fast path cannot build — a custom
plugin, an annotation processor, a `buildSrc`.

The platform work behind it is spike R11 (`tools/rootfs/FINDINGS.md`): there is
no Linux rootfs, and Gradle runs on Termux's Bionic-built OpenJDK started by our
own launcher. What follows is what the *engine* had to learn.

## 1. The command line, not the Tooling API

Gradle's Tooling API exists to start a daemon in another process and talk to it
over a socket. On this platform starting another process is the part that needs
the most care, so going through `org.gradle.launcher.GradleMain` leaves one
process to reason about and one stream to read.

The cost is that progress arrives as console text rather than as events, which
is the next two sections.

## 2. Gradle's task names are not the editor's stages

Gradle has hundreds of tasks, each named per variant; the editor draws a handful
of `BuildStage` values. `GradleOutput` maps the few that correspond to work a
user recognises and lets the rest be notes.

**Every stage is reported once, and that was a bug before it was a rule.** A
dozen tasks map onto the same work — `mergeDebugResources`,
`processDebugResources` and `packageDebugResources` are all resource linking —
and the first version emitted whatever it saw:

```
LINK_RESOURCES, LINK_RESOURCES, LINK_RESOURCES, LINK_RESOURCES,
COMPILE_JAVA, DEX, LINK_RESOURCES, LINK_RESOURCES, DEX, PACKAGE, …
```

That is a progress bar going backwards four times. Deduplicating gives
`LINK_RESOURCES, DEX, COMPILE_JAVA, PACKAGE`, and the test asserts the sequence
has no duplicates — which is what keeps the mapping honest as tasks are added.

**A skipped task is not a stage.** `UP-TO-DATE` and `NO-SOURCE` mean no work
happened, and reporting them would make an incremental build look like a full
one.

The order can still look odd: `DEX` before `COMPILE_JAVA` is normal, because
AGP dexes library dependencies before compiling the project. That is Gradle's
task graph being reported accurately, not a mapping error.

## 3. Gradle's first line often names no cause

A failure block reads:

```
* What went wrong:
Gradle could not start your build.
> Could not determine a usable wildcard IP for this machine.
```

The first line is a category and the indented one is the reason. Reporting only
the headline leaves the user with a sentence they can do nothing with;
reporting the whole block gives them a stack trace. Both lines, joined, is the
useful middle.

## 4. Three things Gradle needs that nothing says out loud

Each was found by hitting it, and none of the messages names the real cause.

**`android.permission.INTERNET`.** Gradle's `FileLockContentionHandler` binds a
socket so other Gradle processes can ask it to release a lock, and an Android
app cannot create a socket without the permission. The failure is *"Could not
determine a usable wildcard IP for this machine."* The module declares the
permission itself rather than leaving it to the app, because an app that
embedded this without it would get that message and no way to connect it to
Gradle.

**`-Djava.io.tmpdir`.** The Termux JDK bakes in Termux's own prefix, which does
not exist for us, and Gradle dies deep inside service construction:
*"java.io.tmpdir is set to a directory that doesn't exist:
/data/data/com.termux/files/usr/tmp"*.

**`useLegacyPackaging` in whatever ships the binaries.** The launcher,
`jspawnhelper` and aapt2 have to be *extracted* to `nativeLibraryDir` at
install; a library left compressed inside the APK has no path on disk at all.
Without it every binary ships and none of them can run — and the symptom is
`JvmToolchain.prepare()` refusing because the launcher "is missing".

## 5. `--no-daemon`, and why it is not a preference

A Gradle daemon that outlives the build holds a heap the size of the build, on
a device that has none to spare. Gradle still forks a *single-use* daemon —
that could not be prevented, and `tools/rootfs/FINDINGS.md` §6 records what was
tried — but it exits with the build.

## 6. The APK is searched for, not computed

Where Gradle writes the APK depends on the variant, the module layout, and any
output renaming the project does — none of which this engine decides. It takes
the newest `*.apk` under `outputs/apk/<variant>/`, so a stale artifact from an
earlier variant is not mistaken for this build's.

## 7. An imported project already knows where its SDK is, and it is wrong

The project M9 exists to build is one that came from somewhere else, and every
such project carries a `local.properties` whose `sdk.dir` names a path on a
desktop. **AGP prefers it to `ANDROID_HOME`**, so exporting the environment
variable and hoping is not enough — the stale line has to go.

Rewriting it is legitimate where rewriting `gradle.properties` would not be.
`local.properties` is machine-specific by definition, gitignored by every
Android template, and Android Studio rewrites it for exactly this reason;
`gradle.properties` is checked in and carries the user's own settings. So
`sdk.dir` is rewritten in place — **and only that key**, because a project may
keep its NDK path or a signing location in the same file, and taking those away
to fix the SDK path trades one broken build for another.

Everything else the engine needs goes on the **command line** instead:
`-Pandroid.aapt2FromMavenOverride=…` is scoped to the invocation and leaves
nothing behind in a file the user owns. `AndroidSdkTest` pins both halves,
including that `gradle.properties` is not touched at all.

The aapt2 that property points at is ours, and the link to it is rebuilt on
every build: `nativeLibraryDir` moves when the app is updated, and a link left
from the previous install fails as a *missing binary* on a device where aapt2 is
plainly installed.

---

## 8. Gradle 9's launcher jar does not contain `GradleMain`

`gradle-launcher-9.7.1.jar` is a thin jar. Its manifest `Class-Path` names
`gradle-gradle-cli-main-9.7.1.jar`, which holds the entry point — and our
launcher hands the JVM a flat `-Djava.class.path`, so nothing expands that
attribute for us.

The failure is unhelpful in a specific way: the JVM starts perfectly and then
cannot find the class it was started for, which the launcher reports as exit 6.
Nothing in that mentions the classpath.

The classpath is now the launcher jar plus every jar its manifest names, read
from the manifest rather than hardcoded — which jar holds the entry point is the
distribution's business, and it has moved once already.

---

## 9. Gradle's own native library is built for glibc

Gradle ships `libnative-platform.so` for `linux-amd64`, meaning glibc. On Bionic
it does not load, and Gradle stops before configuring anything:

```
Could not initialize native services.
> Failed to load native library 'libnative-platform.so' for Linux amd64.
```

`-Dorg.gradle.native=false` is Gradle's own answer for a platform it has no
build for; it falls back to pure-Java file-system and process handling. This is
the difference between "Gradle cannot run here" and a 52-second build.

---

## 10. What AGP actually needs from an SDK, measured

Each of these was established by deleting something and building again, not by
reading AGP's source.

- **`android.jar` alone is enough for the platform.** With `data/`, `optional/`,
  `skins/`, `templates/`, `core-for-system-modules.jar` and the rest of
  `platforms/android-36/` deleted, the build succeeds. This is why the existing
  platform component — which installs one file — is reusable as-is.
- **`build-tools/` must exist and be *complete*, not useful.** Trimmed to only
  the tools AGP runs, the build fails with `Installed Build Tools revision
  36.0.0 is corrupted`. Trimmed instead by dropping `lib64/`, `lld-bin/` and
  `renderscript/` — RenderScript's toolchain, dead since Android 12, and 111 MB
  of the 147 — it passes. So 36 MB is downloaded to be looked at: almost nothing
  in it can execute here (§ the table in Open, below).
- **The licence file's format is exact.** `sdkmanager` writes a leading newline,
  then one hash per line, with no trailing newline. The terms have been revised,
  so a file carrying only the older hash fails with `some licences have not been
  accepted` — which names neither the package nor the fact that a hash is what
  is being compared. Both hashes are written, as `sdkmanager` itself does.
- **Symlinks are fine.** The SDK root is composed out of links into the
  component directories, and AGP follows them without complaint.

---

## 11. Staging as root breaks the app in ways that look like product bugs

Not a finding about the product — a finding about *testing* it, and it cost more
time than anything else here. `GradleBuildSystemTest` skips unless a JDK, a
Gradle distribution and an SDK are staged out of band, and staging them as root
produces failures that read exactly like code faults:

- **`chcon -R` does not relabel symlinks.** Files extracted by root under
  `/data/data` get `app_data_file:s0` without the app's MLS categories, and
  `chcon -R` fixes the regular files while leaving every symlink behind. The app
  then cannot read the link at all. Use `find … -type l -exec chcon -h`.
- **This cost a whole false diagnosis.** The JVM was failing to load `libz.so.1`
  and could not read a single jar, which looked like a real platform limit —
  Termux's JDK needs a SONAME Bionic does not have. A fix was written for it,
  with a confident explanation about app linker namespaces ignoring
  `LD_LIBRARY_PATH`. **All of it was wrong.** The library was unreadable only
  because it is shipped as a symlink and the symlink had the wrong label; with
  the labels fixed, `LD_LIBRARY_PATH` finds it exactly as
  `JvmToolchain.defaultEnvironment` intended, and the fix was reverted. The
  lesson is the repo's own: `run-as` and root are not the app, and a staging
  artefact will happily impersonate a platform limitation.
- **Native libraries need `apk_data_file`.** Installing the test APK by hand can
  leave `nativeLibraryDir`'s `.so` files labelled `app_data_file`, and executing
  app *data* is the one thing the platform forbids — so `libjvmlauncher.so`
  fails with `error=13` while looking perfectly executable. `restorecon -RF` on
  that directory fixes it.

The durable answer is to let the app unpack its own archives, which is what
`unpack()` in the test does when it can see them. Its silence is the trap:
it returns quietly when the archive is missing, so a wrong staging directory
presents as "no JDK staged" rather than as a missing file.

---

## Open

- ~~**There is no SDK component.**~~ **Closed.** `ANDROID_BUILD_TOOLS` installs
  build-tools, and `GradleToolchainProvider.androidSdk()` composes an SDK root
  from it and the existing platform component. §10 is what that rests on.

  The table below is why the download is 60 MB of things that cannot run, and
  is kept because it is the answer to "surely we only need the tools it uses":

  | Entry | What it is | Can it run here |
  |---|---|---|
  | `aapt2`, `zipalign`, `aidl`, `aapt`, `dexdump`, `split-select` | x86_64 ELF, glibc | **No** — wrong architecture on a phone, wrong libc on the emulator |
  | `d8`, `apksigner` | `#!/bin/bash` wrappers | Not as written — Android has `sh`, not `bash` |
  | `lib/d8.jar`, `lib/apksigner.jar` | ordinary jars | **Yes**, on our JVM |
  | `source.properties` | `Pkg.Revision=36.0.0` | metadata; how AGP reads the version |

  What AGP *executes* is our aapt2, via `android.aapt2FromMavenOverride`; it
  dexes and signs with R8 and apksig resolved from Maven, inside its own JVM.
  The rest is inventory it checks and never opens.
- **Heap.** `org.gradle.jvmargs` is set by the test fixture, not the engine.
  What a Gradle build may use on a phone is R3's question, and answering it
  inside this engine would settle it by accident.
- ~~**Multi-module projects** are untried.~~ **Closed.** An application module
  depending on a library module builds on the device in 62 s. It worked
  unchanged, which is the useful part of the result: nothing in the engine
  assumes a single module. `findApk` is the line that could have been wrong —
  it searches rather than computes — so the test asserts the APK came from the
  *application* module, since a library produces none and "the newest output"
  across a multi-module tree is how that goes wrong quietly.
- **Cancellation.** Collecting the flow can be cancelled, but the Gradle process
  is not yet killed when it is.
