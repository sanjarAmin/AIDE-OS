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
:ai:core             Anthropic client, session loop, tools, prompts. FINDINGS.
:ai:ui               The chat panel. Thin: the decisions live in :ai:core
:app                 Compose shell — navigation, DI (Koin), screens
:core:common         Result types, dispatchers, logging
:core:fs             Project storage, the file tree, SAF import. FINDINGS.
:core:ui             Design system, theme, adaptive phone/tablet layout
:editor              sora-editor + tree-sitter, wrapped for Compose. FINDINGS.
:engine:api          The BuildSystem contract; knows nothing of any toolchain
:engine:deps         Maven resolution and AAR extraction, on ART. FINDINGS.
:engine:fast         The bundled pipeline: aapt2 -> ECJ -> D8 -> apksig, plus
                     clang for src/main/cpp. FINDINGS.
:lsp:api             The LanguageService contract; both services implement it
:lsp:java            nb-javac kept warm: completion, diagnostics, definitions
:lsp:native          clangd over stdio, for C and C++
:toolchain:native    aapt2 in jniLibs, and the harness that execs it -- plus the
                     linker64 route for toolchains it did not bundle
:toolchain:manager   Downloads android.jar, kotlinc, clang: pin, verify, install.
                     FINDINGS.
:terminal            forkpty, plus Termux's emulator vendored verbatim. FINDINGS.
:vcs:git             JGit, plus the identity and token stores a device needs. FINDINGS.
tools/               Scripts that produce the toolchains, and their FINDINGS.
```

`docs/PLAN.md` lists 22 modules. Seventeen exist, plus six spikes. Do not
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

- **Koin cannot hold `null` in a singleton.** A `single<T?>` that resolves to
  null throws `Single instance created couldn't return value` and takes every
  dependent definition with it. This crashed every project open for a whole
  milestone before anyone noticed. `AppModuleTest` resolves the workspace graph
  from the real module on a bare device; add to it when something joins the
  graph. `ai/core/FINDINGS.md` §1.
