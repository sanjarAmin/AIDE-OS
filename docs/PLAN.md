# AIDE-OS — On-Device IDE for Android Phones & Tablets

> **Provenance.** This plan was written in a planning session and lived only in
> that session's transcript until 2026-08-21, when it was recovered and
> committed. It is reproduced as written, with eight amendments where reality
> has since diverged; each is marked in place and listed at the end.

## Status

*As of 2026-08-23.*

| | Milestone | State |
|---|---|---|
| ✅ | Spike R1 — aapt2 on-device | Resolved. `tools/aapt2/FINDINGS.md` |
| ✅ | Spike R2 — kotlinc + Compose on ART | Resolved. `tools/kotlinc/FINDINGS.md` |
| ✅ | Spike R2b — ECJ, D8, apksig on ART | Resolved. `tools/ecj/FINDINGS.md` |
| ✅ | License | GPLv3 |
| 🟡 | **M0** Skeleton | Built: `:app`, `:core:{common,fs,ui}`, `:editor`, `:toolchain:{native,manager}`, `:engine:{api,fast}`. 9 of 22 planned modules. |
| 🟢 | **M1** Editor | Highlighting, tabs, symbol row, find/replace, diagnostics gutter, and SAF import. `editor/FINDINGS.md`, `core/fs/FINDINGS.md` |
| 🟢 | **M2** First APK ⭐ | **The thesis holds, and it is reachable.** A person can create a project, edit it, tap Build, and end up with the app installed — all on the device. |

**M2 in detail.** All six stages exist in `:engine:fast` and run on a device:
aapt2 compile → aapt2 link → ECJ → D8 → package → apksig, behind
`BuildSystem.build()`, with `ApkInstaller` handing the result to
PackageInstaller. The instrumented tests build the project template into an APK
that apksig verifies, that the platform's own package parser reads, that the
platform accepts into an install session, and that `pm install` installs with
its launcher activity intact — in well under the 10 s budget, which is an
assertion rather than an aspiration.

`:toolchain:manager` closes the last gap: it downloads the pinned Android SDK
platform from Google's repository, verifies its SHA-1, and extracts
`android.jar` into app storage, gated on the user accepting the Android SDK
Terms. `DownloadedPlatformBuildTest` then builds a project against exactly that
— nothing staged by hand.

**The two are joined.** `WorkspaceScreen` now opens files in the real editor and
its Build button runs the real engine: it saves the buffer, offers the SDK
platform download with Google's licence if the device has never built anything,
streams stage timings and diagnostics into a pane, and hands the signed APK to
PackageInstaller. Driven by hand on an emulator: create a project → open
`MainActivity.java` (highlighted) → Build → accept the licence → 62 MB
downloaded → built in 2.1 s → `com.example.smokeapp` installed. Nothing was
staged from a desktop.

Diagnostics in that pane are tappable: they open the file they name and put the
cursor on the line, which the gutter has underlined.

**M1 in detail.** The editor has tabs — one widget, one buffer per file, so a
tab switch keeps that file's undo history and cursor — a symbol row above the
keyboard for the characters a soft keyboard buries, find/replace over sora's
own searcher, and a gutter fed by the last build's diagnostics. Projects can be
imported from anywhere on the device through the Storage Access Framework.

Importing *copies*, and that is a constraint rather than a shortcut: aapt2
takes filesystem paths and a SAF document has none, so a project left where the
user picked it would be editable and unbuildable. `core/fs/FINDINGS.md` records
that and the three SAF behaviours that cost time to find. Verified by hand: a
Gradle root on shared storage imported (its single module found, `build/` and
`.git/` skipped), built in 2.7 s, installed, and launched showing its own
string resource.

What M1 does not have is anything that needs to understand the code — no
completion, no go-to-definition, no errors before you build. That is M3.

`engine/fast/FINDINGS.md` records what the pipeline cost to assemble — the
install-time constraints that no stage reports, and what is deliberately not
built yet.

Three constraints came out of R2 that `:build:fast` has to honour, rather than
rediscover:

- **API 30 floor.** aapt2's libbase and the compiler dex archive land on the
  same number, so the fast path gates on one version check, not two.
- **`largeHeap`.** The Kotlin front end's object graph does not fit comfortably
  in a default per-app heap.
- **One long-lived compiler classloader.** ~11 s of a one-file compile is
  startup, and it is paid per `PathClassLoader` — so create one and hold it for
  the life of the build process, never per build.

---

## Context

The on-device Android development space has a vacuum right now:

- **AIDE** (appfour) is effectively abandoned — last meaningful release ~2021, targets an ancient SDK, and its genuinely useful capabilities (NDK/C++, Java debugger, Gradle-ish builds) sit behind a paywall. The "cmods" scene exists precisely because the free tier is unusable and the paid tier isn't maintained.
- **AndroidIDE** (itsaky) — the leading open-source alternative — is **archived** as of 2026. A community fork, [AndroidIDE-Rv2](https://github.com/AndroidIDE-Rv2Official), is carrying it forward, but the flagship project is gone.
- **Cosmic IDE** is alive and has solved some hard sub-problems (notably [kotlinc-android](https://github.com/Cosmic-Ide/kotlinc-android)), but is positioned as a general Java/Kotlin IDE, not an Android app builder.

So the target is a modern, open, *fast* IDE for phones and tablets that (a) builds real Android apps on-device, (b) covers C/C++/Java/Kotlin/JS with a path to more, and (c) has first-class AI assistance — something no existing on-device IDE has.

**Project settings:** AGP 9.3.1, Kotlin 2.2.10, Compose BOM 2026.02.01, minSdk 26, targetSdk 37, `namespace = com.osamu.aide`, root project `AIDE-OS`. For what is actually built, see [Status](#status) at the top.

---

## Locked Decisions

| Decision | Choice |
|---|---|
| Build engine | **Hybrid** — bundled fast path (seconds) + optional Gradle-in-userland (compatibility) |
| Foundation | **Fresh build on OSS libraries** — not a fork of AndroidIDE |
| Language scope | C/C++, Java, Kotlin, JavaScript first; C# staged later; then broaden |
| AI | **Core pillar**, bring-your-own API key |

---

## The Critical Architectural Insight

Research turned up a fact that inverts the conventional wisdom, and the whole design hinges on it:

**The fast path needs no Linux userland at all.**

- `ECJ`, `kotlinc`, `D8`/`R8`, `apksig`, and `maven-resolver` are **pure JVM** — they can be dexed into the app and run directly on ART.
- The *only* mandatory native binary is **`aapt2`**. Android 10+ enforces W^X and refuses to execute anything under the app data dir — but `applicationInfo.nativeLibraryDir` is exempt. Ship `aapt2` as `libaapt2.so` inside `jniLibs` and it executes legally on stock, unrooted devices with no PRoot, no root, no Termux.
- Meanwhile **PRoot is fragile on Android 15+** (stricter seccomp filters broke it), and this app targets SDK 37.

**Consequence:** the fast path is the *robust, primary* engine and the Gradle/rootfs path is the *risky, optional* one — the opposite of how AndroidIDE was structured. The app must be fully useful with the rootfs never installed.

Concrete requirements this imposes on `app/build.gradle.kts`:

```kotlin
android {
    packaging { jniLibs { useLegacyPackaging = true } }   // required: extractNativeLibs=true
    // arm64-v8a is what ships; x86_64 exists so the toolchain can be tested on
    // an emulator without emulating a foreign architecture.
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
}
```

---

## Module Architecture

Convert `:app` into a thin Compose shell over a multi-module Gradle build. Add to `settings.gradle.kts`:

```
:app                    Compose shell, navigation, DI, onboarding
:core:common            Result types, coroutine dispatchers, structured logging
:core:fs                Project storage, SAF + direct file IO, file watching
:core:ui                Design system, adaptive phone/tablet layouts, keyboard/stylus input

:editor                 sora-editor wrapper: tabs, search/replace, symbol row, diagnostics gutter
:editor:languages       tree-sitter grammars + TextMate themes per language

:lsp:client             JSON-RPC / LSP client (in-process and pipe transports)
:lsp:java               Java intelligence (javac/JDT-based, in-process)
:lsp:kotlin             Kotlin Analysis API
:lsp:clangd             clangd over pipe transport

:engine:api             BuildSystem interface, BuildRequest/BuildResult, progress event stream
:engine:fast            ECJ/kotlinc -> D8 -> aapt2 -> apksig -> PackageInstaller
:engine:gradle          Gradle Tooling API bridge into the rootfs
:engine:deps            Maven resolution (maven-resolver), AAR extraction, artifact cache

:toolchain:native       jniLibs packaging for aapt2/clang/lld, exec harness, W^X handling
:toolchain:manager      Component download, checksum verification, install, version pinning

:terminal               PTY terminal emulator widget
:runtime:linux          Rootfs bootstrap, PRoot + linker64/LD_PRELOAD exec strategies

:ai:core                Anthropic client, session state, tool definitions, context assembly
:ai:ui                  Chat panel, inline completion, quick-fix affordances

:vcs:git                JGit — clone/commit/diff/push
:debugger               JDWP client for on-device Java/Kotlin debugging
```

Existing files touched: `settings.gradle.kts` (module includes), `gradle/libs.versions.toml` (version catalog for every dep below), `app/build.gradle.kts` (packaging/ABI config), `MainActivity.kt` (replaced by the real shell).

---

## Key External Dependencies

Reuse aggressively — none of these should be written from scratch:

| Need | Library | License | Note |
|---|---|---|---|
| Code editor widget | [sora-editor](https://github.com/Rosemoe/sora-editor) | LGPL-2.1 | Maven Central. Keep **unmodified**; upstream patches. |
| Syntax parsing | tree-sitter (via sora `language-treesitter`) | MIT | Incremental AST, fast highlighting |
| Java compiler | Eclipse ECJ (`org.eclipse.jdt.core.compiler.batch`) | EPL-2.0 | Pure JVM, incremental, error-tolerant |
| Kotlin compiler | stock `kotlin-compiler-embeddable`, dexed by `tools/kotlinc/` | Apache-2.0 | **Superseded the plan.** Cosmic IDE's port was evaluated; building our own from the current release tracks upstream instead of a fork. See `tools/kotlinc/FINDINGS.md`. |
| Dexing | D8/R8 (`com.android.tools:r8`) | Apache-2.0 | Pure JVM, includes desugaring |
| Resources | `aapt2` built from AOSP with NDK | Apache-2.0 | **Highest-effort native component** |
| APK signing | `apksig` | Apache-2.0 | Pure JVM; also handles alignment |
| Dependency resolution | Apache `maven-resolver` | Apache-2.0 | Pure JVM Maven/AAR resolution |
| Git | JGit | BSD-3 | Pure JVM |
| C/C++ | clang/lld (Termux aarch64 builds) + NDK sysroot | Apache-2.0 w/ LLVM | |
| AI | `com.anthropic:anthropic-java` (OkHttp backend) | MIT | Android-compatible |

---

## The Fast Build Pipeline

This is the product's core differentiator — target **under 10 seconds** edit→run on a mid-range device.

```
1. aapt2 compile   res/**            -> *.flat            [native, jniLibs]
2. aapt2 link      *.flat + AARs     -> base.apk + R.java [native, jniLibs]
3. ECJ / kotlinc   src/** + R.java   -> *.class           [JVM on ART]
4. D8              *.class + deps    -> classes*.dex      [JVM on ART]
5. apksig          merge + align + sign -> app-debug.apk  [JVM on ART]
6. PackageInstaller session API      -> install           [no root; user confirms]
```

Design notes:

- **Everything except steps 1–2 runs in-process on ART.** No subprocess, no rootfs.
- Run compilation in a **separate `:build` process** with `android:largeHeap="true"` so a kotlinc OOM kills the builder, not the editor, and the editor's memory isn't taxed by compiler heap.
- **Incrementality is the whole game.** Hash-based up-to-date checks per stage; only recompile changed source sets; keep a warm classpath index. Aim for the common single-file edit to skip steps 1–2 entirely.
- `:build:deps` resolves AndroidX and friends from Maven Central/Google Maven, extracts AARs (`classes.jar` + `res/` + `R.txt`), and feeds `res/` into aapt2 with `--auto-add-overlay`.
- Project model is **our own manifest** (a simple TOML/JSON project descriptor), with a *best-effort Gradle importer* that parses common `build.gradle.kts` shapes into it. Projects too complex to import fall back to the Gradle path.

---

## Language Rollout

Ordered by (value × feasibility), matching your stated priority:

**Phase A — Java + Kotlin (fast path).** The baseline. ECJ + kotlinc + D8 + aapt2, full LSP intelligence. Compose support was flagged as a real risk here; spike R2 settled it — the plugin is dexed *beside* the compiler in one archive and registered with `-Xplugin`. Compose is fast-path.

**Phase B — C/C++ (fast path).** clang/lld for aarch64 + NDK sysroot headers/libs, shipped via `:toolchain:manager` as an optional ~400 MB download. Compile to `.so`, package into the APK, `clangd` for intelligence. **This is the exact capability the AIDE cmods scene pirates the app for** — shipping it free is a strong wedge.

**Phase C — JavaScript.** Two tiers: QuickJS built with the NDK (small, embeddable, runs as a scripting/automation engine with zero rootfs) and full Node.js aarch64 in the rootfs for real npm projects. React Native / Capacitor Android builds need Node **and** Gradle, so they're rootfs-track.

**Phase D — Gradle path + C# (rootfs).** Full AGP compatibility for projects the fast path can't handle, plus the .NET SDK.

> **Honest constraint on C#:** the .NET SDK ships as glibc/linux-arm64 binaries, so it cannot use the fast path — it *requires* the rootfs, inheriting all of the PRoot fragility. And .NET Android app builds need MAUI/`dotnet-android` workloads that are enormous on-device. Plan for C# console/library compilation as the realistic Phase D deliverable, and treat .NET *Android app* builds as experimental. Java/Kotlin/C++ will feel like first-class citizens; C# will not, and that's a property of the .NET toolchain, not of our design.

**Phase E — broaden.** Dart/Flutter, Rust (`cargo-ndk`), Python (Chaquopy-style or Kivy), Lua. Each is a `:toolchain` plugin + a `:lsp` module against interfaces already established.

---

## AI Layer (`:ai:core`)

Bring-your-own-key, no backend infrastructure, no per-user liability for you.

- **SDK:** `com.anthropic:anthropic-java` via `AnthropicOkHttpClient` (OkHttp works fine on Android).
- **Model:** `claude-opus-5` with `thinking: {type: "adaptive"}` and `output_config.effort` — `low` for inline completion, `high` for chat and agentic edits.
- **Streaming everywhere.** Token-by-token into the chat panel; also prevents HTTP timeouts on long agentic turns.
- **Prompt caching is the cost lever, and it dictates prompt layout.** Caching is prefix-match, rendered `tools` → `system` → `messages`. So: stable tool definitions and system prompt first, then the project context block (file tree, open buffers, relevant symbols) behind a `cache_control: {type: "ephemeral"}` breakpoint, and only *then* the volatile user turn. Anything time-varying (timestamps, cursor position) must sit after the last breakpoint or it silently invalidates the whole cache. Verify with `usage.cache_read_input_tokens` — if it's zero across turns, something upstream is churning.
- **Tool use turns the assistant into an agent:** `read_file`, `edit_file`, `grep`, `list_files`, `run_build`, `read_build_errors`. Gate every mutating tool behind an explicit user confirmation in the UI.
- **Killer feature:** pipe compiler diagnostics straight into a fix suggestion. On-device builds produce errors on a device with no Stack Overflow tab open — one-tap "explain and fix this error" is worth more here than on desktop.
- **Key storage:** Android Keystore-backed encrypted preferences. Never log the key; never ship a default key.

---

## Roadmap

| Milestone | Deliverable | Acceptance test |
|---|---|---|
| **M0** Skeleton | Multi-module build, Compose shell, DI, adaptive phone/tablet layout | App launches, navigates, no features |
| **M1** Editor | sora-editor + tree-sitter, tabs, file tree, SAF, search/replace, symbol row | Open a 5k-line Java file: highlighting correct, scrolling at 60fps |
| **M2** First APK ⭐ | `:toolchain:native` (aapt2 in jniLibs), `:build:fast` for Java, PackageInstaller | Hello-world Java project builds + installs in **< 10s**. *This is the make-or-break milestone.* |
| **M3** Intelligence | `:lsp:java`, completion, diagnostics-as-you-type, go-to-definition | Completion on AndroidX types < 200ms |
| **M4** Deps + Kotlin | maven-resolver, AAR extraction, kotlinc integration | Project with `androidx.appcompat` + Kotlin sources builds |
| **M5** AI ⭐ | `:ai:core` + `:ai:ui`, chat, inline completion, fix-my-error | BYO key → chat with project context, one-tap error fix works |
| **M6** Compose | Compose compiler plugin hosted in on-device kotlinc | A Compose hello-world builds and runs |
| **M7** C/C++ | clang/lld toolchain download, NDK sysroot, clangd | JNI project with a native `.so` builds |
| **M8** Git + Terminal | JGit, PTY terminal | Clone from GitHub, edit, commit, push |
| **M9** Gradle path | Rootfs bootstrap, Tooling API bridge | An unmodified Android Studio project builds |
| **M10** JS / C# | QuickJS + Node; .NET SDK (experimental) | Node project runs; C# console app compiles |

M0–M5 is the real v1.0. Everything from M6 on is expansion.

---

## Risk Register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| ~~R1~~ | ~~Building `aapt2` from AOSP for Android/aarch64~~ | **CLOSED** | Resolved. Builds clean for arm64-v8a and x86_64; ours is PIE where AndroidIDE's prebuilt is not, so the fallback was not needed. `tools/aapt2/FINDINGS.md`. |
| ~~R2~~ | ~~Compose compiler plugin inside on-device kotlinc~~ | **CLOSED** | Resolved. Compiler and plugin dex into one archive; startup needed seven separate fixes, all recorded in `tools/kotlinc/FINDINGS.md`. The "route Compose to the Gradle path" fallback is retired. |
| R3 | kotlinc memory footprint (1 GB+) OOMs on mid-range devices | Medium | Separate `:build` process, `largeHeap`, no incremental Kotlin initially, degrade gracefully with a clear message |
| R4 | PRoot broken on Android 15+ seccomp | Medium — bounded by design | Fast path never touches PRoot. Rootfs is opt-in. Fallback exec strategies: `linker64` direct invocation, `termux-exec` LD_PRELOAD interception. |
| R5 | Google Play policy on code-executing apps | Medium | Primary channels **F-Droid + direct APK + GitHub Releases**. Play as a secondary, possibly feature-reduced build. AIDE is on Play, so it's not categorically banned. |
| R6 | sora-editor LGPL-2.1 obligations | Low | Consume as an **unmodified** Maven dependency; upstream any changes; document the relink path. Keeps the rest of the app under your chosen license. |
| R7 | Scope — this is a genuinely large project | **High** | Milestones are ordered so M2 proves the riskiest thesis early. If M2 slips badly, reconsider the AndroidIDE-Rv2 fork before sinking further cost. |

**Licensing: decided — GPLv3.** It keeps the option of vendoring from AndroidIDE / AndroidIDE-Rv2, both GPLv3, which is a meaningful shortcut on a project this size; and it prevents a closed-source fork repeating AIDE's history, which is the reason this project exists. The cost is Google Play friction, already bounded by R5 making F-Droid the primary channel.

---

## Verification Strategy

- **Golden project corpus** in `:build:fast` instrumentation tests — a fixture set (Java hello-world, Kotlin + AndroidX, Compose app, JNI project, multi-module) built end-to-end on a real device in CI, asserting APK validity and wall-clock build time. This is the regression net for the entire build engine.
- **Build-time budgets as failing tests**, not aspirations: M2's "< 10s hello-world" is an assertion, and a PR that regresses it fails.
- **`:lsp` correctness**: fixture files with expected completion/diagnostic positions.
- **Manual device matrix**: one low-RAM (4 GB) phone, one flagship, one tablet, spanning Android 10 / 14 / 16 — because W^X, PRoot, and exec behavior all vary by OS version and are invisible on an emulator.
- **`:ai:core`**: mock the Anthropic transport for loop/tool logic; assert `cache_read_input_tokens > 0` on the second turn of a live smoke test to catch silent cache invalidation.

---

## Immediate Next Steps

1. ~~**Spike R1 (aapt2)**~~ — done.
2. ~~**Spike R2 (Compose plugin in on-device kotlinc)**~~ — done.
3. ~~**Spike R3 (Java language services on ART)**~~ — done. `tools/javals/FINDINGS.md`.
   ~~**Spike R4 (Maven resolution on ART)**~~ — done. Resolves AndroidX
   transitively, but needed four workarounds and rules out maven-resolver 2.x.
   `tools/deps/FINDINGS.md`.
4. ~~**Pick a license**~~ — done, GPLv3.
5. ~~**M0 skeleton**~~, ~~**M1 editor**~~, ~~**M2 first APK**~~ — closed. Hello-world
   builds and installs in **2.76 s** against the 10 s budget.
6. ~~**M3 intelligence**~~ — **closed**. Completion, diagnostics-as-you-type,
   go-to-definition and signature hints, verified rendering in the running app.
   The acceptance test is met as written: completion on an **AndroidX** type at a
   **76 ms** warm median against the 200 ms budget, once `:engine:deps` could put
   `appcompat` on the classpath.
7. **M4 deps + Kotlin** — the current target, half done. `:engine:deps` resolves
   and unpacks AndroidX, and its classpath now reaches ECJ, D8, aapt2 (as
   overlaid resource archives) and `:lsp:java`. What remains is the Kotlin half:
   the compiler dex archive exists and spike R2 proved it runs, but nothing
   wires kotlinc into the engine.

~~Before building `:build:fast`, verify the remaining "pure JVM, therefore fine
on ART" assumptions — ECJ, D8/R8 and apksig.~~ Done: spike R2b. All three run,
but not for free — ECJ is pinned to 3.38.0 and needs `platform-stubs.jar` on the
compile classpath before it will accept a lambda. `tools/ecj/FINDINGS.md`.

ECJ also needs a `javax.lang.model.SourceVersion` to exist at all, which Android
does not provide. That was a hand-written shim until `:lsp:java` arrived with
nb-javac's real one and the two collided in a single APK; the shim is gone and
the real class serves both. `engine/fast/FINDINGS.md` section 10 — it is the
sharpest example so far of a bug no module's own test suite can see.


---

## Amendments

Made 2026-08-21, when this document was recovered from the session transcript
and committed. The plan is otherwise as originally written.

1. **Current state of the repo** — described an untouched template; replaced
   with a pointer to [Status](#status).
2. **ABI filters** — `arm64-v8a` only; both ABIs now ship, because x86_64 is
   what makes emulator testing possible without emulating a foreign
   architecture.
3. **Kotlin compiler** — the dependency table named Cosmic IDE's
   `kotlinc-android`. Spike R2 built our own dex archive from the stock
   `kotlin-compiler-embeddable` release instead, which tracks upstream rather
   than a fork.
4. **Phase A** — Compose on the fast path was flagged as an open risk; R2
   settled it.
5. **R1** — closed. Our aapt2 build is PIE where AndroidIDE's prebuilt is not,
   so the planned fallback was never needed.
6. **R2** — closed, and the "route Compose to the Gradle path" fallback retired.
7. **Licensing** — was an open question; decided as GPLv3, with the reasoning
   recorded inline.
8. **Immediate Next Steps** — rewritten to reflect what is done, plus a new
   prerequisite: verify ECJ, D8/R8 and apksig actually run on ART before
   designing the fast engine around the assumption that they do. Done — R2b.
9. **`:build:*` renamed `:engine:*`** — a source directory named `build/` is the
   root project's own Gradle output directory. `gradlew clean` would delete it
   and `.gitignore` would hide it. Naming only; the plan's structure is intact.
