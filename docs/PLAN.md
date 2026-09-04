# AIDE-OS — On-Device IDE for Android Phones & Tablets

> **Provenance.** This plan was written in a planning session and lived only in
> that session's transcript until 2026-08-21, when it was recovered and
> committed. It is reproduced as written, with ten amendments where reality
> has since diverged; each is marked in place and listed at the end.

## Status

*As of 2026-09-03.*

| | Milestone | State |
|---|---|---|
| ✅ | Spike R1 — aapt2 on-device | Resolved. `tools/aapt2/FINDINGS.md` |
| ✅ | Spike R2 — kotlinc + Compose on ART | Resolved. `tools/kotlinc/FINDINGS.md` |
| ✅ | Spike R2b — ECJ, D8, apksig on ART | Resolved. `tools/ecj/FINDINGS.md` |
| ✅ | Spike R6 — JGit on ART | Resolved, and **unmodified** — the first "pure JVM" claim here that held. `tools/git/FINDINGS.md` |
| ✅ | Spike R7 — PTY + shell on a device | Resolved, and retired into `:terminal`. `forkpty` works from an untrusted app, **job control included**. `tools/pty/FINDINGS.md` |
| ✅ | Spike R9 — executing a downloaded binary | Resolved. Via `/system/bin/linker64`, from **internal** storage only. `tools/nativeexec/FINDINGS.md` |
| ✅ | Spike R11 — the Gradle path | The rootfs route is **closed** and **unnecessary**. Termux's OpenJDK 21 is a Bionic binary; a launcher of ours calling `JNI_CreateJavaVM` runs it in the app's own process, and with the few binaries a build execs symlinked to copies in `nativeLibraryDir`, **AGP 9.3.2 builds an Android APK on the device** — including one that resolves **androidx.appcompat**, merges its manifests and links its resources: 3.2 MB, 55 s — plus Kotlin, and **Compose** with its compiler plugin verified by the transformation it leaves in the dex. `tools/rootfs/FINDINGS.md` |
| ✅ | Spike R12 — Kotlin intelligence | **It answers on ART.** A standalone Analysis API session builds on a device and resolves `String` to `kotlin/String` and `List<Int>` to `kotlin/collections/List<kotlin/Int>` — resolution, not parsing. The API is not in what we ship and not on Maven Central, so it is fetched from JetBrains' own repository, **relocated onto the compiler's shaded namespace** (9204 references; the two are otherwise incompatible) and dexed to a 1.9 MB archive. Getting there took flattening the plugin descriptors, adding kotlinx-serialization, finding that kotlinx-collections-immutable was never a missing dependency but a **missed relocation**, and then three shims — Caffeine's `StripedBuffer`, `javax.management`, `javax.swing.SwingUtilities` — each only visible once the one before it was gone. **Latency settles the milestone's real question:** building the session costs 1808 ms and a query against it costs **4 ms** (median of 42; a repeat is 0 ms), against M3's 200 ms budget. The cost is startup, not answering — so the session must be resident, the way `:lsp:java` holds a warm javac. **And it answers about a buffer**, which is what an editor actually asks: a dangling file bound to a resident session resolves text that was never written to disk, sees an edit the session has not, and reports a type error before any build — **59 ms warm** against Java completion's 76 ms, though that was a session with no library module; the shipping configuration resolves against android.jar and the stdlib and costs ~230 ms. **Libraries are read on ART** — kotlin-stdlib.jar as a `KtLibraryModule` from plain bytecode. The remaining gap is extension completion, and it is research rather than plumbing: the star-importing scope will not enumerate its name universe (76 extension symbols, two distinct names, where the default imports hold thousands), so `String` completes to eight members and not `uppercase`. Enumerate-then-filter is the wrong shape — adding the stdlib cost 4× the latency (59 → 229 ms) and changed the answer not at all. It needs a name index over *binary* libraries; the provider with the right method is source-only. The archive is also still staged by hand — `:toolchain:manager` has no component for it, which is ordinary work. **Retired into `:lsp:kotlin`**, which implements the same `LanguageService` the editor already talks to; `LanguageServices` routes `.kt` and `.kts` to it, and fourteen instrumented tests drive it as the editor does. One upload short of reaching a device: the component is packaged and pinned, the release asset is not published. `tools/analysisapi/FINDINGS.md` §12–21 |
| ✅ | Spike R10 — clang on the device | Resolved, on **API 34 x86_64 and Android 16 arm64**. Termux clang 21.1.8 builds C and C++ into a `.so` that loads and runs. **One job per invocation, and the driver can never run the linker** — plan with `-###`, execute it ourselves. `tools/clang/FINDINGS.md` |
| ✅ | License | GPLv3 |
| 🟢 | **M0** Skeleton | `:app`, `:core:{common,fs,ui}`, `:editor`, `:engine:{api,fast,deps}`, `:toolchain:{native,manager}`, `:lsp:java`, `:ai:{core,ui}`, `:terminal`, `:vcs:git` — 15 of 22 planned modules, plus six spikes. The remaining seven arrive with the milestones that need them. |
| 🟢 | **M1** Editor | Highlighting, tabs, symbol row, find/replace, diagnostics gutter, and SAF import. `editor/FINDINGS.md`, `core/fs/FINDINGS.md` |
| 🟢 | **M2** First APK ⭐ | **The thesis holds, and it is reachable.** A person can create a project, edit it, tap Build, and end up with the app installed — all on the device. |
| 🟢 | **M3** Intelligence | Completion, diagnostics-as-you-type, go-to-definition, signature hints. **76 ms** warm completion on an AndroidX type against the 200 ms budget. |
| 🟢 | **M4** Deps + Kotlin | A Kotlin project using `androidx.appcompat` builds on device: 41 artifacts resolved, kotlinc ahead of ECJ. Resolution reads Gradle Module Metadata, so AndroidX aligns the way Gradle aligns it. `engine/deps/FINDINGS.md` |
| 🟡 | **M5** AI ⭐ | Feature-complete, and **multi-provider** since 2026-09-02: Gemini (default, with Google Sign-In), OpenAI, anything OpenAI-compatible, and Anthropic. Three assertions still parked until a live API key exists — the milestone is **waiting on credentials, not on code**. `ai/core/FINDINGS.md` §§11–14 cover the provider work, including that `AiSession` now has two tool loops sharing nothing but tool execution |
| 🟢 | **M6** Compose | A Compose app builds, installs, launches and **draws**, on device, with its libraries' manifests merged. Six fixes, none visible to a build-only test. `engine/deps/FINDINGS.md` |
| 🟢 | **M10** Kotlin intelligence | `:lsp:kotlin` answers about a Kotlin buffer on device: placed diagnostics before any build, completion that resolves a receiver declared only in the buffer, filtered by the typed prefix and following an edit the session has not seen. A third shape of language service — in-process like javac, but behind a classloader nothing in the app can name, so every call crosses into a backend shipped inside the archive. Driven by hand in the running app: a Kotlin project's `MainActivity.kt` opens clean and completes `this.setC` to `setContentView(View!)` out of **`android.jar`** — which is how two bugs were found that seven passing tests missed (there are fourteen now). **Extensions work**: `String` offers `uppercase`, and a `MutableMap` extension is correctly withheld from it. The Analysis API resolves a top-level callable by name and will list none — three enumeration routes return nothing for a binary library — so the names are read from **`@kotlin.Metadata`** — the protobuf the compiler wrote — over the session's own library jars at startup, which covers a project's AARs and tells extensions from ordinary functions before anything is resolved. **Warm completion is ~230 ms** against the 200 ms budget M3 holds Java to at 76 ms; the cost is library resolution, not extensions — the same session answers in 59 ms with no library module, which is the configuration nobody ships. Not reachable on a real device until the component's release asset is uploaded. `tools/analysisapi/FINDINGS.md` §17–21 |
| 🟢 | **M7** C/C++ | A JNI project builds on device: clang compiles `src/main/cpp`, the library is packaged into the APK, and it loads and runs. **clangd answers too** — diagnostics, completion, go-to-definition and hover for C and C++, through the same interface the Java service implements. Verified on API 34 x86_64 and Android 16 arm64. `tools/clang/FINDINGS.md` |
| 🟢 | **M9** Gradle path | `:engine:gradle` builds an Android project on the device with the project's own Gradle, on Termux's OpenJDK started by our launcher — accepted by the platform's package parser, and a **two-module** project builds too. Verified on x86_64 and on Android 16 arm64, which took disabling heap pointer tagging the emulator could never have shown. **Nothing is staged by hand any more**: the JDK, Gradle and build-tools install themselves, and the SDK root is composed from them. `engine/gradle/FINDINGS.md`, `tools/rootfs/FINDINGS.md` |
| 🟢 | **M8** Git + Terminal | Clone, edit, stage, commit, push and diff, with identity and tokens in the Keystore. A real terminal: Termux's emulator vendored unmodified, characters sent as typed. `vcs/git/FINDINGS.md`, `terminal/FINDINGS.md` |

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
- **AndroidIDE** (itsaky) — the leading open-source alternative — is **archived** as of 2026. Two forks carry it forward: [AndroidIDE-Rv2](https://github.com/AndroidIDE-Rv2Official), and [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) (appdevforall), which is the more active of the two — GPLv3, issue-tracked, commits through August 2026, and further along on Kotlin than anything else in this space: refactorings, find-usages and go-to-definition on the K2 Analysis API, a SQLite symbol index, a UI designer and a Compose preview. It is worth reading rather than competing with blind; `tools/analysisapi/FINDINGS.md` §20 records what it does differently and what should be taken from it.
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
:engine:gradle          Gradle on the device's own JVM (no rootfs — see spike R11)
:engine:deps            Maven resolution (maven-resolver), AAR extraction, artifact cache

:toolchain:native       jniLibs packaging for aapt2/clang/lld, exec harness, W^X handling
:toolchain:manager      Component download, checksum verification, install, version pinning

:terminal               PTY terminal emulator widget
:runtime:linux          ~~Rootfs bootstrap, PRoot~~ — not needed; spike R11 closed that route

:ai:core                Provider clients, session state, tool definitions, context assembly
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

**Phase B — C/C++ (fast path).** clang/lld for aarch64 + NDK sysroot headers/libs, shipped via `:toolchain:manager` as an optional download **into internal storage, executed through `/system/bin/linker64`** — see spikes R9 and R10. Spike R10 measured the real cost: 100 MB down, **600 MB installed**, which is well past the ~400 MB estimated here and is the number to design the download around. It also found the two rules the native stage has to obey — one compiler job per invocation, and the driver cannot execute `ld.lld` at all, so the link is planned with `-###` and run by us. This paragraph originally contradicted the W^X note above it: a download lands in app data, where `execve` is refused. It is the dynamic linker that makes it possible, and external storage cannot host it at all. Compile to `.so`, package into the APK, `clangd` for intelligence. **This is the exact capability the AIDE cmods scene pirates the app for** — shipping it free is a strong wedge.

**Phase C — JavaScript.** Two tiers: QuickJS built with the NDK (small, embeddable, runs as a scripting/automation engine with zero rootfs) and full Node.js aarch64 in the rootfs for real npm projects. React Native / Capacitor Android builds need Node **and** Gradle, so they're rootfs-track.

**Phase D — Gradle path + C# (rootfs).** Full AGP compatibility for projects the fast path can't handle, plus the .NET SDK.

> **Honest constraint on C#:** the .NET SDK ships as glibc/linux-arm64 binaries, so it cannot use the fast path — it *requires* the rootfs, inheriting all of the PRoot fragility. And .NET Android app builds need MAUI/`dotnet-android` workloads that are enormous on-device. Plan for C# console/library compilation as the realistic Phase D deliverable, and treat .NET *Android app* builds as experimental. Java/Kotlin/C++ will feel like first-class citizens; C# will not, and that's a property of the .NET toolchain, not of our design.

**Phase E — broaden.** Dart/Flutter, Rust (`cargo-ndk`), Python (Chaquopy-style or Kivy), Lua. Each is a `:toolchain` plugin + a `:lsp` module against interfaces already established.

---

## AI Layer (`:ai:core`)

Bring-your-own-key, no backend infrastructure, no per-user liability for you.

- **SDK:** `com.anthropic:anthropic-java` via `AnthropicOkHttpClient` (OkHttp works fine on Android). The other three providers are hand-rolled over OkHttp and `org.json`, because each speaks its own format and none ships an Android-friendly SDK worth the dependency.
- **Model:** `claude-opus-5` with `thinking: {type: "adaptive"}` and `output_config.effort` — `low` for inline completion, `high` for chat and agentic edits. **Model IDs live in exactly one place**, `AiProviderType`; the clients derive their defaults from it. They were written in five places once and drifted, and a retired ID fails at no build or startup check — only as a 404 on the first request, which reads to the user as the assistant being broken.
- **Streaming everywhere.** Token-by-token into the chat panel; also prevents HTTP timeouts on long agentic turns.
- **Prompt caching is the cost lever, and it dictates prompt layout.** Caching is prefix-match, rendered `tools` → `system` → `messages`. So: stable tool definitions and system prompt first, then the project context block (file tree, open buffers, relevant symbols) behind a `cache_control: {type: "ephemeral"}` breakpoint, and only *then* the volatile user turn. Anything time-varying (timestamps, cursor position) must sit after the last breakpoint or it silently invalidates the whole cache. Verify with `usage.cache_read_input_tokens` — if it's zero across turns, something upstream is churning.
- **Tool use turns the assistant into an agent:** `read_file`, `edit_file`, `grep`, `list_files`, `run_build`, `read_build_errors`. Gate every mutating tool behind an explicit user confirmation in the UI.
- **Killer feature:** pipe compiler diagnostics straight into a fix suggestion. On-device builds produce errors on a device with no Stack Overflow tab open — one-tap "explain and fix this error" is worth more here than on desktop.
- **Key storage:** Android Keystore-backed encrypted preferences. Never log the key; never ship a default key.
- **The endpoint is configurable, and that is the whole multi-provider story for now.** *(Amended — see [Amendment 10](#amendments). A provider interface exists as of 2026-09-02. The paragraph below is kept because its reasoning turned out to be right about the shape of the thing.)* `AnthropicOkHttpClient.baseUrl` points the same SDK at anything speaking this wire format — a self-hosted proxy, a gateway in front of another model. It costs one builder call and no abstraction. A genuinely different provider (Gemini, an OpenAI-shaped API) is a port rather than a setting: implicit prefix caching and verbatim thinking-block replay have no equivalent elsewhere, so `PromptAssembler`'s entire design would have nothing to do. Do not build a provider interface before something needs one — it would be the intersection of the two, which is the API minus everything M5 was built around.

- **What the provider interface actually became.** `AiClient` did *not* subsume the Anthropic path, exactly as predicted above. It sits beside it: `AiSession` holds two loops, `sendAnthropic` over the SDK's own types and `sendGeneric` over `AiClient`, sharing nothing but tool execution. The Anthropic path keeps `PromptAssembler`, its cache breakpoints and verbatim thinking replay; the generic path has none of those because none of the other providers offer them. The provider-neutral vocabulary is deliberately thin — text, thought, function call, function response — and even that leaks: Gemini has no call ids and matches a result to its call by function name, while OpenAI rejects a `tool` message whose `tool_call_id` it did not issue. **The cost of the second loop is that every rule M5 paid for had to be re-established in it, and the existing suite went on passing throughout**, because all of it drove the other path. Anything added to one loop needs deciding for the other.

---

## Roadmap

| Milestone | Deliverable | Acceptance test |
|---|---|---|
| **M0** Skeleton | Multi-module build, Compose shell, DI, adaptive phone/tablet layout | App launches, navigates, no features |
| **M1** Editor | sora-editor + tree-sitter, tabs, file tree, SAF, search/replace, symbol row | Open a 5k-line Java file: highlighting correct, scrolling at 60fps |
| **M2** First APK ⭐ | `:toolchain:native` (aapt2 in jniLibs), `:build:fast` for Java, PackageInstaller | Hello-world Java project builds + installs in **< 10s**. *This is the make-or-break milestone.* |
| **M3** Intelligence | `:lsp:java`, completion, diagnostics-as-you-type, go-to-definition | Completion on AndroidX types < 200ms |
| **M4** Deps + Kotlin | maven-resolver, AAR extraction, kotlinc integration | Project with `androidx.appcompat` + Kotlin sources builds |
| **M5** AI ⭐ | `:ai:core` + `:ai:ui`, chat, inline completion, fix-my-error — **plus a provider interface**: Gemini (default, Google Sign-In or key), OpenAI, OpenAI-compatible, Anthropic | BYO key → chat with project context, one-tap error fix works. Still needs a live key to close |
| ✅ **M6** Compose | Compose compiler plugin hosted in on-device kotlinc | A Compose hello-world builds and runs |
| **M7** C/C++ | Termux clang/lld toolchain download, NDK sysroot, clangd | JNI project with a native `.so` builds — **met**, clangd included |
| **M8** Git + Terminal | JGit, PTY terminal | Clone from GitHub, edit, commit, push |
| **M9** Gradle path | ~~Rootfs bootstrap~~ → Termux's **Bionic-built OpenJDK 21**, a launcher of our own, and `:engine:gradle` | **Met.** An unmodified Android Studio project builds on device through the `BuildSystem` interface, on both ABIs and with more than one module. Every part installs itself: OpenJDK from this repo's releases, Gradle pinned to its own publisher, build-tools from Google, and an SDK root composed from the last two |
| **M10** JS / C# | QuickJS + Node; .NET SDK (experimental) | Node project runs; C# console app compiles |

M0–M5 is the real v1.0. Everything from M6 on is expansion.

---

## Risk Register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| ~~R1~~ | ~~Building `aapt2` from AOSP for Android/aarch64~~ | **CLOSED** | Resolved. Builds clean for arm64-v8a and x86_64; ours is PIE where AndroidIDE's prebuilt is not, so the fallback was not needed. `tools/aapt2/FINDINGS.md`. |
| ~~R2~~ | ~~Compose compiler plugin inside on-device kotlinc~~ | **CLOSED** | Resolved. Compiler and plugin dex into one archive; startup needed seven separate fixes, all recorded in `tools/kotlinc/FINDINGS.md`. The "route Compose to the Gradle path" fallback is retired. |
| R3 | kotlinc memory footprint (1 GB+) OOMs on mid-range devices | **Mitigated** | The separate `:build` process exists and is **verified by driving the app**, not only by tests: create a project, tap Build, and the six stages stream out of `com.osamu.aide:build` into the UI — resources 20 ms, link 269 ms, javac 1664 ms, D8 2011 ms, package, sign — then the APK installs and the built app draws. An out-of-memory kill now takes the build and leaves the editor and its unsaved buffers standing |
| ~~R4~~ | ~~PRoot broken on Android 15+ seccomp~~ | **CLOSED — and it was the wrong worry** | PRoot is not broken: on Android 16 it starts, ptraces its child and rewrites paths. What cannot happen is the *guest*. An app on a modern `targetSdk` may not `execve` its own files, and `linker64` is Bionic's loader and cannot host a musl program; PRoot rewrites paths but grants no permission and supplies no second libc. So the rootfs route is closed unless `targetSdk` drops to 28, which is what Termux does. It is also unnecessary — see the M9 row. `tools/rootfs/FINDINGS.md` |
| R5 | Google Play policy on code-executing apps | Medium | Primary channels **F-Droid + direct APK + GitHub Releases**. Play as a secondary, possibly feature-reduced build. AIDE is on Play, so it's not categorically banned. |
| R6 | sora-editor LGPL-2.1 obligations | Low | Consume as an **unmodified** Maven dependency; upstream any changes; document the relink path. Keeps the rest of the app under your chosen license. |
| R9 | The `linker64` exec route closing in a future Android | Medium — M7 rests on it | The gap it uses (`noexec` mount, `PROT_EXEC` mapping permitted) is not a documented guarantee. Measured working on API 34, and **on Android 16 / API 36 arm64 on a vendor ROM** — two API levels on, it has not closed. Spike R10 exercised it with a real toolchain and it held, at the cost of two workarounds for what the route breaks (`/proc/self/exe`, and spawning child tools). `tools/nativeexec/FINDINGS.md`, `tools/clang/FINDINGS.md` §6. The manual device matrix is the early warning, and `:toolchain:native`'s harness already supports more than one strategy. |
| R8 | Vendored third-party source drifting from upstream | Low | Termux's terminal emulator is **Apache 2.0** (not GPLv3 — the repo grants that module an exception) and is vendored **byte-identical**, with the commit and checksums in `terminal/vendor/PROVENANCE.md`. One file of ours replaces one upstream interface so the rest need no edits, which makes an update a copy rather than a merge. |
| R7 | Scope — this is a genuinely large project | **High** | Milestones are ordered so M2 proves the riskiest thesis early. If M2 slips badly, reconsider the AndroidIDE-Rv2 fork before sinking further cost. |

**Licensing: decided — GPLv3.** It keeps the option of vendoring from AndroidIDE / AndroidIDE-Rv2, both GPLv3, which is a meaningful shortcut on a project this size; and it prevents a closed-source fork repeating AIDE's history, which is the reason this project exists. The cost is Google Play friction, already bounded by R5 making F-Droid the primary channel.

---

## Verification Strategy

- **Golden project corpus** in `:build:fast` instrumentation tests — a fixture set (Java hello-world, Kotlin + AndroidX, Compose app, JNI project, multi-module) built end-to-end on a real device in CI, asserting APK validity and wall-clock build time. This is the regression net for the entire build engine.
- **Build-time budgets as failing tests**, not aspirations: M2's "< 10s hello-world" is an assertion, and a PR that regresses it fails.
- **`:lsp` correctness**: fixture files with expected completion/diagnostic positions.
- **Manual device matrix**: one low-RAM (4 GB) phone, one flagship, one tablet, spanning Android 10 / 14 / 16 — because W^X, PRoot, and exec behavior all vary by OS version and are invisible on an emulator.
- **`:ai:core`**: mock the transport for loop/tool logic; assert `cache_read_input_tokens > 0` on the second turn of a live smoke test to catch silent cache invalidation. **There are two loops to cover, not one** — `sendAnthropic` and `sendGeneric` share nothing but tool execution, so a green suite over either says nothing about the other. Drive the real provider clients against a local server rather than a fake `AiClient`: the wire format is where a provider rejects a request, and a fake never emits one.

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
7. ~~**M4 deps + Kotlin**~~ — **closed**. A Kotlin project using
   `androidx.appcompat` builds to an APK on device: 41 artifacts resolved and
   unpacked, resources overlaid, kotlinc ahead of ECJ, D8 over the lot. The
   compiler is downloaded from this project's own releases by
   `:toolchain:manager`, verified against a pinned checksum, and the download
   is tested against the live release rather than a fixture.
8. **M5 AI** — **feature-complete, one assertion short of closed.** Chat with
   project context, six tools behind a confirmation gate that fails closed,
   one-tap error fix from any diagnostic, and inline completion at the cursor.
   Keyless where it can be: the session loop, the approval handshake and the
   completion request are all tested on device against a local Messages API
   rather than the real one.

   What is *not* closed is the acceptance test as written: "BYO key → chat"
   needs a key, and so do the two questions a fake cannot answer — whether
   the live API accepts this request shape, and whether prompt caching reports a
   hit. Those stay skipped in `:spike:ai`.

   **Deliberately parked, not forgotten.** M5 closes when a key exists to run
   those three against; later milestones do not wait on it. The command is in
   `ai/core/FINDINGS.md`. Nothing else in the roadmap depends on the answer —
   if the live API rejects the request shape, the fix is in `PromptAssembler`
   and touches no other module.

   `ai/core/FINDINGS.md` is the milestone's other deliverable. Its theme is that
   M5's characteristic bug **returns a correct answer** — a lost cache, a
   dropped thinking block, an absolute path the tools refuse — so almost every
   rule there has a test that fails when the line is removed rather than a
   comment asking the next person to be careful.

   It also records the worst bug of the milestone, which was not in the AI layer
   at all: Koin cannot hold `null` in a singleton, so `single<KotlinCompiler?>`
   had been crashing every project open on any device without the Kotlin
   toolchain since M4 — the state every new user is in.

   **M5 went multi-provider on 2026-09-02**, which the AI Layer section covers
   in full. What matters at roadmap level: `AiSession` now has *two* tool loops,
   the acceptance test is unchanged and still unmet for want of a key, and two
   things are knowingly unfinished. The Google OAuth client ID in
   `GoogleAuthManager` is a placeholder, so Sign-In cannot complete until a real
   client is registered for the package — Gemini by API key is unaffected. And
   `ai/core/FINDINGS.md`, the milestone's other deliverable, has not been
   updated for any of it; three providers and a second tool loop arrived without
   a line in the document that is supposed to explain them.

9. **M6 Compose — the build half is done and proven.** Spike R2 had already put
   the Compose plugin in the same dex archive as the compiler, and
   `KotlinCompiler` already registered it with `-Xplugin` on finding
   `androidx.compose.runtime.Composer` on the classpath; what was missing was
   any test that the path worked from a project on disk. `ComposeBuildTest` is
   that test: 11 artifacts resolved, kotlinc, ECJ, D8 and aapt2 over the lot, a
   2 MB APK, in ~70 s on the emulator.

   It asserts the **transformed bytecode**, not the exit code, and that
   distinction is not theoretical. Disabling the `-Xplugin` registration makes
   the build **still succeed** — it is only the assertion that `GreetingKt`
   references `Composer`, a class its source never names, that catches it. That
   is R2's finding 7 reproduced one layer out, and it is why "a Compose project
   compiles cleanly" would have been a worthless test.

   **M6 is now closed, "runs" included.** `ComposeRunTest` builds a Compose
   app with `activity-compose` and `foundation`, installs it, launches it, and
   waits for UiAutomator to find its marker text in the running app's
   accessibility tree. It draws.

   Getting from "builds" to "runs" took six fixes, and **every one was
   invisible to a build-only test**: the build reported success, the APK
   verified, `pm install` succeeded, `am start` said `Status: ok`, and the app
   crashed. Five were dependency resolution — AndroidX's graph is correct only
   under Gradle Module Metadata, and maven-resolver reads `.pom`. The sixth was
   aapt2 generating no `R` class for any library package.

   The new document is `engine/deps/FINDINGS.md`; `engine/fast/FINDINGS.md`
   section 12 has this module's half. The honest summary at the time was that
   two of the three gaps were papered over with curated tables that would rot,
   and the fix that does not is reading `.module` files. **That is now
   written.** `:engine:deps` reads Gradle Module Metadata, which is where all
   three mechanisms actually live; the absorption table was deleted and
   verified gone by emptying it and watching this same end-to-end test still
   build, install and draw.

10. **M8 Git + Terminal — spiked, and the git half is unblocked.** Spike R6
   ran JGit 7.7.1 on a device: `FS.detect()`, init, add, commit, `RevWalk`,
   a shallow clone over HTTPS and a push, all passing on the first run with
   **no workarounds at all**. That is the first time "pure JVM, therefore fine
   on ART" has been true in this project, and it is worth saying out loud after
   kotlinc, ECJ and maven-resolver each cost days.

   What the spike changes about the design, rather than confirms:
   `FS.detect()` reports no system config and no user home, so there is no
   `user.name`, no `~/.gitconfig` and no credential helper — **identity and
   tokens are ours to store**, Keystore-backed, following `ApiKeyStore`. And a
   depth-1 clone of a large repository took 254 s and 179 MB, which is a
   bandwidth number rather than a JGit one but still puts clone in the same
   category as `:toolchain:manager`'s downloads: off the main thread, cancellable,
   with real progress and a storage figure shown before it is spent.

   SSH is deliberately unanswered; HTTPS with a token is the designed path.
   `tools/git/FINDINGS.md`.

   **The git half is now done, end to end, and driven by hand on a device.**
   Create a repository from the panel, stage, commit with the identity entered
   in Settings: `master / add4029 First commit from a phone`, with the ref on
   disk. Cloning, its progress dialog and its cancel were driven the same way.

   That exercise found four bugs no test could have: the git panel was
   unreachable without first starting a build, a created project could never
   become a repository, the changed-files list was laid out at zero height in a
   dock sized for build output, and identity validation reddened the wrong
   field. None is a logic error, which is why a suite that drives view models
   saw none of them. `vcs/git/FINDINGS.md` section 8.

   `:vcs:git` wraps JGit;
   `GitViewModelTest` drives the acceptance test's own words at the view-model
   layer -- edit, stage, commit, push -- and asserts the commit in the object
   database and again in the *receiving* repository, because JGit reports a
   rejected push through a status rather than by throwing.
   `ProjectsCloneTest` covers the other half: a repository clones into the
   workspace and turns up as a project.

   Two things are parked rather than done. Pushing to **GitHub** specifically
   needs a real token, which sits in the same category as M5's live-API
   assertions -- spike R6 proved the HTTPS transport and the tests prove the
   pack generation, so what is unverified is only whether GitHub accepts the
   result. And **SSH is deliberately unanswered**: it is a separate JGit
   artifact and a separate key-management problem, and HTTPS with a token is
   the designed path.

   Six bugs were found by tests rather than by review, and the two worth
   remembering are the same bug twice: a refresh that clears `errorMessage` on
   success, called *after* an operation that set one, made two different
   failures completely silent. `vcs/git/FINDINGS.md` section 7.

   **The terminal half is spiked, and the platform is not the problem.** Spike
   R7 asked the question that could have sunk it: can an unprivileged Android
   app open a pseudoterminal and run a shell with working job control? It can.
   `forkpty` is permitted from an `untrusted_app` domain, `/system/bin/sh`
   execs on the slave, a foreground command gets its own process group, and
   signalling that group returns the terminal to the shell. No root, no rootfs,
   no PRoot, none of the exec gymnastics R1 needed for aapt2.

   This is the first risk here that was not "does this JVM library run on ART" —
   there is no PTY API in Java at all — so it is also the first that needed
   native code. One file of C, built with `ndk-build`.

   Three things came out of it that shape `:terminal` rather than confirm it.
   The child must reset the signal dispositions it inherits from the JVM, or the
   shell starts with `SIGINT` ignored and looks exactly like a platform without
   job control. An app can **exec** from `/system/bin` but cannot **list** it,
   so command completion cannot work by directory listing. And a shell survives
   the app being backgrounded, unthrottled — 44 heartbeats in 45 seconds, three
   times over — though that was measured under instrumentation, which is
   precisely the state in which Android relaxes cached-app handling, so
   `:terminal` should still plan for a foreground service.

   **The process half now exists as `:terminal`**, and the spike is deleted:
   `TerminalSession` runs a shell, pumps its output as a flow of bytes, reports
   the size, interrupts the foreground group and separates stopping the reader
   from stopping the shell. Bytes rather than text on purpose — a multi-byte
   character can straddle two reads, and decoding is the emulator's job because
   only it can hold the partial sequence.

   **M8 is closed. The emulator is vendored from Termux**, byte-identical, with
   provenance and checksums in `terminal/vendor/PROVENANCE.md`. It turned out to
   be **Apache 2.0** rather than GPLv3 — the repository grants `terminal-emulator`
   an exception because it descends from jackpal's Android-Terminal-Emulator —
   which is worth knowing, because it changes the obligation from copyleft to
   attribution.

   Only the emulator was taken; Termux's own process handling was left behind,
   since spike R7's already existed and was tested. Every vendored file is
   unmodified, which cost exactly one file of ours: a replacement for the one
   upstream interface whose signatures would have dragged the rest in.

   The Terminal tab now sends characters as they are typed, and the difference
   vendoring made is one assertion: `printf 'AAAA\rBB'` leaves `BBAA` on one
   line. The previous implementation stripped escape sequences and could only
   have produced two lines. Driven by hand: `ls -a` in a project's own folder.

   Two bugs the tests caught and typing at it would not have: input launched a
   coroutine per keystroke and therefore **arrived out of order**, so an arrow
   key landed after the character it should have preceded; and the input channel
   was reused across restarts, so a dead shell's consumer went on stealing
   roughly half of what was typed. `terminal/FINDINGS.md` sections 5 and 4.

~~Before building `:build:fast`, verify the remaining "pure JVM, therefore fine
on ART" assumptions — ECJ, D8/R8 and apksig.~~ Done: spike R2b. All three run,
but not for free — ECJ is pinned to 3.38.0 and needs `platform-stubs.jar` on the
compile classpath before it will accept a lambda. `tools/ecj/FINDINGS.md`.

ECJ also needs a `javax.lang.model.SourceVersion` to exist at all, which Android
does not provide. That was a hand-written shim until `:lsp:java` arrived with
nb-javac's real one and the two collided in a single APK; the shim is gone and
the real class serves both. `engine/fast/FINDINGS.md` section 10 — it is the
sharpest example so far of a bug no module's own test suite can see.

11. **M7 C/C++ — the acceptance test is met.** A JNI project builds on device:
    clang compiles `src/main/cpp` one job at a time, we plan the link with
    `-###` and run `ld.lld` ourselves, and the library is packaged into the APK
    at `lib/<abi>/`. The test extracts that library from the built APK, loads it
    into the test process and asserts the marker its `JNI_OnLoad` wrote — an
    entry in a zip proves nothing about a `.so` that might be corrupt, built for
    the wrong architecture, or linked against something absent. Green on API 34
    x86_64 and on Android 16 / API 36 arm64.

    Two spikes stand behind it. R9 established that a downloaded binary runs
    only from internal storage and only through `/system/bin/linker64`; R10 put
    a real toolchain on that route and found the two rules the engine had to be
    built around — one compiler job per invocation, and the driver can never
    execute the linker. Both are consequences of app storage being
    non-executable, not defects, and both are pinned by tests that assert
    *failure* so the workarounds cannot quietly become unnecessary without
    anyone noticing.

    | Module | What it does now |
    |---|---|
    | `:toolchain:manager` | Downloads a 152 MiB gzipped tar per ABI from this repo's releases and unpacks it **in-process**, symlinks and permission bits preserved |
    | `:toolchain:native` | `LinkerLaunch` starts a binary it did not bundle; `ClangToolchain` supplies the five flags the launch stops clang deriving, and plans-then-executes links |
    | `:engine:fast` | `NativeCompileStage`, `ClangDiagnostics`, and `lib/<abi>/` in the APK — with `libc++_shared.so` beside anything C++ |
    | `:app` | Offers the download when a native project is built without it |

    **clangd is done too.** `:lsp:native` drives it over stdio and implements
    the same `LanguageService` the Java side does, so the editor cannot tell
    which answered; `:lsp:api` holds that contract. It was not obviously
    possible — clangd's usual way of finding system headers is to execute the
    compiler and ask it, which this platform forbids — and the way through is a
    `compile_flags.txt` and never `--query-driver`. Two ways it answers
    *wrongly* rather than not at all are recorded in `tools/clang/FINDINGS.md`
    §9; both were found by building the client and neither reports an error.

    Two other limits, both deliberate. The APK is built for the device's own ABI
    and no other, because each additional one is another 551 MB toolchain to
    produce libraries this device cannot run. And 551 MB is a great deal to ask:
    roughly 115 MB of it is LLVM tools a compile never touches, and trimming is
    a real opportunity that is deliberately not taken blind, since `llvm-ar` and
    `llvm-strip` are wanted the moment static libraries are.


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

Made 2026-09-02:

10. **A provider interface exists** — the AI Layer section said not to build one
    before something needed it, and predicted that one would be "the API minus
    everything M5 was built around." Something needed it: the assistant now
    speaks to Gemini, OpenAI and OpenAI-compatible endpoints as well as
    Anthropic, with Gemini the default.

    The prediction was right about the shape, which is why the original
    paragraph is kept rather than replaced. The interface did **not** become the
    intersection of the two APIs — that would indeed have been worthless.
    `AiClient` sits *beside* the Anthropic path instead of subsuming it, and the
    price is a second tool loop with its own copy of every rule the first one
    learned. Read as a warning it still reads correctly; read as a prohibition
    it was overtaken.
