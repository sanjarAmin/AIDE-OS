# AIDE-OS — working notes

An on-device Android IDE. Read [`docs/PLAN.md`](docs/PLAN.md) first; it holds
the architecture, the roadmap, and the risk register, and it is the document
that decides what to build next.

## Commands

There is no `java` on `PATH`; Gradle fails outright without `JAVA_HOME`.

```sh
export JAVA_HOME=/opt/android-studio/jbr        # Java 25
export PATH="$JAVA_HOME/bin:$HOME/Android/Sdk/platform-tools:$PATH"

./gradlew test                                   # JVM unit tests
./gradlew connectedDebugAndroidTest              # needs a device, API 30+
```

The SDK's tools are not on `PATH` either — `~/Android/Sdk/platform-tools/adb`,
`~/Android/Sdk/emulator/emulator`. The test AVD is `aideos_test` (API 34,
x86_64) and boots headless in about 40 seconds:

```sh
emulator -avd aideos_test -no-window -no-audio -no-boot-anim -gpu host
```

## Layout

```
:ai:core             Provider clients, session loop, tools, prompts. Two loops:
                     one Anthropic, one for the rest. FINDINGS.
:ai:ui               The chat panel. Thin: the decisions live in :ai:core
:app                 Compose shell — navigation, DI (Koin), screens
:core:common         Result types, dispatchers, logging
:core:fs             Project storage, the file tree, SAF import. FINDINGS.
:core:ui             Design system, theme, adaptive phone/tablet layout
:debugger            A JDWP client, and the agent the build puts in a debug APK.
                     Not "attach to any app": an app cannot reach another's
                     JDWP, so we debug what we built. FINDINGS.
:editor              sora-editor + tree-sitter, wrapped for Compose. FINDINGS.
:engine:api          The BuildSystem and RunSystem contracts; knows nothing of
                     any toolchain
:engine:deps         Maven resolution and AAR extraction, on ART. FINDINGS.
:engine:fast         The bundled pipeline: aapt2 -> ECJ -> D8 -> apksig, plus
                     clang for src/main/cpp. FINDINGS.
:engine:gradle       The other engine: the project's own Gradle, on a real JVM
:engine:node         Runs a Node project. A sibling to the two above, not a
                     third of them: they produce an APK, this starts a program
:engine:mono         The same, for C#: mcs and then the assembly, so a run is
                     two processes and reports two starts
:lsp:api             The LanguageService contract; both services implement it
:lsp:java            nb-javac kept warm: completion, diagnostics, definitions
:lsp:native          clangd over stdio, for C and C++
:lsp:node            `node --check` for JavaScript: syntax diagnostics only,
                     and the FINDINGS say why that is the whole promise
:toolchain:native    aapt2, a JVM launcher and jspawnhelper in jniLibs, and the
                     harnesses that drive them -- plus the linker64 route for
                     toolchains it did not bundle
:toolchain:manager   Downloads android.jar, kotlinc, clang: pin, verify, install.
                     FINDINGS.
:terminal            forkpty, plus Termux's emulator vendored verbatim. FINDINGS.
:vcs:git             JGit, plus the identity and token stores a device needs. FINDINGS.
tools/               Scripts that produce the toolchains and the one grammar
                     nobody publishes, and their FINDINGS.
                     Several assemble from Termux's package repo, because only
                     Bionic-linked binaries start here: clang, a JDK, node, mono
```

`docs/PLAN.md` lists 22 modules. Twenty-two exist, plus ten spikes. Do not
create the rest speculatively — each arrives with the milestone that needs it.

## Conventions

**Findings are part of the deliverable.** When a spike or investigation
establishes something non-obvious — a platform limitation, a dead end, a
version floor — it goes in a `FINDINGS.md` next to the code, written so someone
who wasn't there can act on it. `tools/aapt2/FINDINGS.md` and
`tools/kotlinc/FINDINGS.md` are the models. This repo's history is short and its
constraints are unusual; the documents are how they survive.

**Comments explain why, not what.** Existing code is dense with rationale for
decisions that look arbitrary — why the compiler's classloader is parented to
the boot loader, why aapt2's output is read from stderr. Match that. A comment
restating the code is noise; a comment recording the failure that motivated the
line is the point.

**Tests pin down platform behaviour, not just logic.** The valuable tests here
are instrumented ones asserting that a thing works *on a device* — that a binary
execs from `nativeLibraryDir`, that emitted bytecode was really transformed.
Assert the observable effect, not the exit code: a compiler plugin that silently
failed to load still produces a clean compile.

## Things that will bite

- **API 30 is the toolchain floor.** aapt2's libbase needs it, and the compiler
  dex archive is built for it. `minSdk` stays 26 — the editor works below 30 —
  so build features must gate at runtime, not fail at exec time.
- **A real device dozes, and keeping it awake breaks the Compose tests.** Two
  failures that look unrelated and pull in opposite directions, both only on
  hardware.

  With the screen off the phone reaches `mWakefulness=Dozing` and suspends a
  test process mid-run: a suite that takes one second runs for ten minutes and
  then reports `Process crashed`. That is not a test defect. `adb shell svc
  power stayon usb` fixes it — and then every Compose UI test fails with
  `No compose hierarchies found in the app`, because the screen being on means
  the **lock screen** is in front of the activity. `wm dismiss-keyguard` does
  not help on a device with a secure lock.

  **A locked phone cannot finish a full sweep either way.** Run
  `connectedDebugAndroidTest` against the emulator, which neither dozes nor
  locks, and use the phone for targeted runs: `stayon usb` first, the suites
  you care about via `am instrument`, then `svc power stayon false` after. A
  device left plugged in unattended will be dozing when you come back to it, so
  check before believing a stall. `adb shell dumpsys power | grep mWakefulness`
  and `adb shell dumpsys window | grep isKeyguardShowing`.

- **`Process crashed` from `am instrument` carries no information.** No Java
  exception, no failure count, the log stopping after the first test starts.
  Seen twice in one day from two unrelated causes: a sweep killed
  mid-instrumentation had *uninstalled the test package*, and Android's own
  `com.android.providers.media.module` aborted in its FUSE node tracker --
  the daemon behind `/sdcard`, which any suite staging archives through
  `getExternalFilesDir` is reading from. **Check `/data/tombstones` first**: a
  tombstone means a native abort and names the process, so it rules our Java
  code out in thirty seconds, and its absence rules the platform out just as
  fast. `tools/analysisapi/FINDINGS.md` §23.

- **A skip reports as OK, so read the skip count and not just the failure
  count.** A sweep of 636 tests with 0 failures was skipping 76 of them, and
  five modules — every one of M10's — were skipping *all* of their tests
  because `gradle/stage-device-archives.gradle.kts` had never been told where
  `node.tar` and `mono.tar` live. The archives were on the machine. Nothing in
  the output said so louder than a warning nobody reads.

  ```sh
  # after a sweep: which modules skipped, and how much. One line on purpose --
  # an indented multi-line -c string is an IndentationError when it is pasted.
  python3 -c "import glob,re;print('\n'.join(f\"{f.split('/build/')[0]} {re.search(r'skipped=.(\d+).',open(f).read()).group(1)} of {re.search(r'tests=.(\d+).',open(f).read()).group(1)} skipped\" for f in glob.glob('**/build/outputs/androidTest-results/connected/**/*.xml',recursive=True) if re.search(r'skipped=.([1-9]\d*).',open(f).read())))"
  ```

  **Twenty-four skips is the honest floor**, measured on a full sweep: eight
  live-API tests in `:ai:core` waiting on billing credit, nine spikes needing a
  rootfs nothing builds any more, five in `:toolchain:manager` gated behind
  `-Pandroid.testInstrumentationRunnerArguments.downloadTests=true` because they
  pull real archives from Google and GitHub, and two singletons. **Anything
  above that is an archive on the wrong path, and a milestone's worth of
  coverage doing nothing.**

  Count from a *full* sweep, not from whatever XML happens to be on disk: a
  targeted `-Pandroid.testInstrumentationRunnerArguments.class=...` run
  overwrites that module's results file with its own handful of tests, so a
  census taken afterwards silently omits the module. That is how the floor was
  first recorded as nineteen.

- **`adb shell run-as` is not the app.** It runs in `runas_app`, which *may*
  `execve` out of app-private storage — so a hand probe through it will
  cheerfully do things the app is forbidden to do, and appear to disprove a
  finding. Only an instrumented test in the app's own process settles a
  question about exec or SELinux. `tools/clang/FINDINGS.md` §7.

- **clang runs on device, but the driver cannot spawn anything.** Compiling
  and linking in one `clang` invocation fails, and so does letting clang run
  `ld.lld` at all — both because a spawned tool has to `execve` out of app
  storage. One job per invocation, and links are planned with `-###` and
  executed by us. `tools/clang/FINDINGS.md`.

- **The kotlinc dex archive's inputs are not in git, but they are pinned.**
  ~100 MB of third-party jars, consumed by `tools/kotlinc/build-shim.sh` and
  `build-kotlinc-dex.py`. `tools/kotlinc/fetch-jars.sh` rebuilds all nine from
  `jars.lock` and verifies each against a recorded sha256, so the set is
  reproducible from a clean machine — it was not, for a long time, and the
  warning that it isn't outlived the fix.
- **`grep` here is a shell function that skips binary files.** Use
  `/usr/bin/grep -a` when searching class files, jars, or dex.

- **A Compose `Row` squeezes rather than overflows, and no obvious assertion
  sees it.** Four `FilterChip`s in the new-project dialog: the last was laid
  out 19 dp wide against its neighbour's 99, its label wrapped inside it, and
  it shipped unreadable. `assertIsDisplayed()` is **true** of that chip — it is
  laid out and its bounds are in the window. Comparing its right edge against
  the container is true too, and always will be: a `Row` clamps to the space it
  has, so the edge lands exactly on the boundary. What separates the two
  layouts is **height** — a chip too narrow for its label wraps the label and
  grows taller, 40.4 dp against 32. Assert that sibling chips are the same
  height, and use `FlowRow` when there are more than three.
  `CreateProjectDialogTest`.

  **This is the most common defect in this codebase: seven instances so far.**
  The shape is always a `Row` holding a variable-width label beside a control,
  with no `weight` on the label. The label measures at whatever width it wants,
  the control is measured in the remainder, and the remainder is often nothing:
  a settings `Switch` at `Rect(0, 0, 0, 0)`, a `Remove` button 61 px wide
  instead of 197 and 360 px tall with its label running down the screen, a dock
  whose close button drew nothing while the dock covered half the editor.
  **The control is usually the consequential half of the row** — Save, Remove,
  Restart the shell, revoke this token, close this panel — because that is the
  half a designer puts on the right.

  So: **`Modifier.weight(1f)` on the label, every time**, and `horizontalScroll`
  as well when the row genuinely holds more than fits (the dock's five tabs, the
  chat suggestions, the terminal key row). Assert it by comparing the control
  against **the same control in a row with a short label** — a sibling, never
  the container — since a `Row` never reports bounds past its own edge.
  `EditorSectionTest`, `ToolchainSectionTest`, `GitSectionTest`,
  `BottomToolDockTest`.

  **Drive at 360 dp before believing a row is fine.** Every instrumented test
  runs at the emulator's default width, and the dock's tabs looked correct
  there and were broken on an ordinary small phone: `adb shell wm size
  720x1600` with `wm density 320`, then `wm size reset` / `wm density reset`.

- **Koin cannot hold `null` in a singleton.** A `single<T?>` that resolves to
  null throws `Single instance created couldn't return value` and takes every
  dependent definition with it. This crashed every project open for a whole
  milestone before anyone noticed. `AppModuleTest` resolves the workspace graph
  from the real module on a bare device; add to it when something joins the
  graph. `ai/core/FINDINGS.md` §1.

- **A `connectedAndroidTest` run uninstalls the app, and projects go with
  it.** Projects live in `/sdcard/Android/data/com.osamu.aide/files/projects`,
  which is external *app* storage: Gradle uninstalls the app when the run
  finishes, and Android deletes that directory with it. A project created by
  hand to drive the app is gone after the next suite, and the symptom is not an
  error -- the app is simply not running and the emulator is showing its
  launcher, which reads as a crash. `dumpsys activity exit-info` empty and
  `/data/tombstones` unchanged is the tell that nothing died. **Drive after the
  test runs, not between them.**

- **Android freezes background apps, and a frozen app looks like a hung
  protocol.** Android 14's cached-app freezer stops a process that is not on
  screen and not referenced by one that is. It keeps its sockets open and
  answers nothing: the debugger's Step simply never came back, with no log and
  no exception. The tell is `do_freezer_trap` in
  `/proc/<pid>/task/<pid>/wchan`, or `cch` in `dumpsys activity processes`.
  SIGQUIT and `debuggerd -b` both fail on a frozen process, which is itself a
  clue. Anything here that talks to *another app* -- the debugger today -- has
  to keep it referenced from the foreground, and **not by binding a service in
  it**: service creation runs on that app's main thread, which may be the thread
  being debugged, and the app is killed for an ANR. `debugger/FINDINGS.md` §9.

- **`ls a* b*` in zsh aborts on the first pattern that matches nothing**, and
  `2>/dev/null` hides the reason. `ls LICENSE* NOTICE*` printed nothing at all
  in a repo that has had a `LICENSE` since its second commit, because `NOTICE*`
  matched none — which read as "there is no LICENSE" and led to a commit
  message asserting exactly that. Check one glob at a time, or use
  `setopt NO_NOMATCH` / `git ls-files`, and treat empty output from a globbing
  command as *unknown* rather than as *absent*.
