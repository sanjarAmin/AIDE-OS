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

## Open

- **There is still no SDK component.** The JDK and Gradle install themselves
  now, and the engine configures the project itself, but the SDK is still staged
  by hand for the tests — which is why `GradleBuildSystemTest` skips on a
  machine that has not staged one.

  `ToolchainComponent.ANDROID_PLATFORM` does **not** close this. It installs a
  bare `android.jar`, which is all `:engine:fast` ever needed; AGP wants an SDK
  *directory* — `platforms/android-36/`, a `build-tools/` beside it, and an
  accepted `licenses/android-sdk-license`.

  **And build-tools cannot simply be downloaded.** Google publishes them as
  Linux x86_64 binaries, which is the same wall aapt2 hit and is why §7 exists.
  What AGP actually reads out of that directory when it runs here, and which of
  it must be a native binary rather than a jar, is not known — the working SDK
  the tests use was assembled by hand and nobody wrote down what was in it.
  **That is a spike, not an afternoon**, and it is the last thing between M9 and
  closed.
- **Heap.** `org.gradle.jvmargs` is set by the test fixture, not the engine.
  What a Gradle build may use on a phone is R3's question, and answering it
  inside this engine would settle it by accident.
- **Multi-module projects** are untried. Everything built so far is one module,
  and an Android Studio project is almost never one module — so M9's acceptance
  test is weaker than it reads.
- **Cancellation.** Collecting the flow can be cancelled, but the Gradle process
  is not yet killed when it is.
