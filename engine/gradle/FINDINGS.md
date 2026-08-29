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

## Open

- **Installation.** The JDK, Gradle and an Android SDK are staged by hand for
  the tests. `:toolchain:manager` has no component for any of them, and hosting
  a ~300 MB JDK is a decision for the project owner.
- **aapt2.** AGP fetches a **Linux x86_64** aapt2 from Maven, which cannot run
  here at all. `android.aapt2FromMavenOverride` has to point at ours, and AGP
  insists the path be named `aapt2` — so a symlink, not the `.so` directly.
  Nothing in the engine writes that property yet; the tests do it by hand.
- **Multi-module projects** are untried. Everything built so far is one module.
- **Cancellation.** Collecting the flow can be cancelled, but the Gradle process
  is not yet killed when it is.
