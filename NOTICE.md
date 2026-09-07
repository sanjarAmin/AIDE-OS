# Third-party components

AIDE-OS is GPLv3; see [`LICENSE`](LICENSE). What follows is what it ships or
downloads that somebody else wrote, and the licence each is under. Every entry
is sourced from this repository's own records — the version catalogue, the
provenance files, and the `tools/*/FINDINGS.md` that describe how each toolchain
was assembled — rather than from memory.

## Bundled in the APK

| Component | Licence | Where it is recorded |
|---|---|---|
| **sora-editor** (`io.github.Rosemoe.sora-editor`) | LGPL-2.1 | `docs/PLAN.md` risk R6, which is why it is consumed as an **unmodified** Maven dependency |
| **Termux terminal emulator** (`com.termux.terminal`, vendored byte-identical) | Apache 2.0 | `terminal/vendor/PROVENANCE.md` — the termux-app repository is GPLv3 *with a recorded exception* for this module |
| **android-tree-sitter** and its grammars (`com.itsaky.androidide.treesitter`) | see upstream | grammar *queries* are vendored per language and listed in `editor/src/main/assets/treesitter/README.md`, all MIT |
| **tree-sitter-javascript** — built here, not downloaded | MIT | `tools/treesitter/FINDINGS.md`; built from upstream's pinned v0.23.1 release |
| **aapt2**, built from AOSP | Apache 2.0 | `tools/aapt2/FINDINGS.md` |
| **ECJ** (`org.eclipse.jdt:ecj`) | EPL-2.0 | `tools/ecj/FINDINGS.md` |
| **JGit** (`org.eclipse.jgit`) | EDL (BSD-3-Clause) | `tools/git/FINDINGS.md` |
| **OkHttp**, **anthropic-java** | Apache 2.0 | `gradle/libs.versions.toml` |

## Downloaded on demand

These are not in the APK. `:toolchain:manager` fetches them, each pinned by
checksum, and the Toolchains screen in Settings lists what is installed.

| Component | Licence | Where it is recorded |
|---|---|---|
| **Kotlin compiler** and **Kotlin Analysis API** | Apache 2.0 | `tools/kotlinc/FINDINGS.md`, `tools/analysisapi/FINDINGS.md` |
| **clang / LLVM**, from Termux's packages | Apache 2.0 with LLVM exception | `tools/clang/FINDINGS.md` |
| **OpenJDK 21**, from Termux's packages | GPLv2 with the Classpath Exception | `tools/rootfs/FINDINGS.md` |
| **Gradle** | Apache 2.0 | pinned to its own publisher rather than re-hosted |
| **Node.js** and **npm**, from Termux's packages | MIT | `tools/node/FINDINGS.md` |
| **Mono**, from Termux's packages | MIT | `tools/mono/FINDINGS.md` |
| **android.jar** and **build-tools**, from Google | Android SDK Terms and Conditions | accepted in the app before either is fetched; `SdkLicense` |

## What this list is not

It is not a substitute for the licence texts themselves, which travel with each
component. Where a licence requires the text to be conveyed, the component's own
archive carries it; this file records **which** licence applies to what, because
that is the question that is hard to answer later and easy to answer now.
