# AIDE-OS

An on-device IDE for Android phones and tablets that builds real Android apps —
no root, no Termux, no Linux userland.

The design hinges on one fact: almost the entire toolchain is pure JVM and runs
directly on ART. The only mandatory native binary is `aapt2`, and Android's W^X
policy exempts `nativeLibraryDir`, so shipping it as `libaapt2.so` lets it
execute on a stock, unrooted device. That makes the fast build path the
*primary* engine rather than a fallback — the opposite of how existing on-device
IDEs are structured.

See [`docs/PLAN.md`](docs/PLAN.md) for the full architecture, roadmap, and risk
register.

## Status

Working, early. A person can create a project on the device — or import one
from anywhere through the system file picker — edit it with syntax
highlighting, tabs, and find/replace, tap Build, and have the APK compiled,
signed, and installed in a few seconds. Nothing staged from a desktop.

| | |
|---|---|
| **M1 Editor** | sora-editor + tree-sitter: tabs, symbol row, find/replace, a diagnostics gutter fed by the build, SAF import. [Findings](editor/FINDINGS.md), [more](core/fs/FINDINGS.md) |
| **M2 First APK** | aapt2 → ECJ → D8 → apksig, entirely on-device; the SDK platform downloaded and verified on first build. [Findings](engine/fast/FINDINGS.md) |

The spikes that de-risked the design — aapt2 built from AOSP, kotlinc + the
Compose plugin on ART, ECJ/D8/apksig on ART — are recorded under
[`tools/`](tools/), each with its own `FINDINGS.md`.

Next milestone is **M3**: Java language intelligence — completion,
diagnostics as you type, go-to-definition.

## Building

There is no `java` on `PATH` in the usual Android Studio setup, so Gradle needs
`JAVA_HOME` pointed at a JDK:

```sh
JAVA_HOME=/path/to/jdk ./gradlew build
```

Instrumented tests need a connected device or emulator running **API 30 or
newer** — the toolchain's floor, for reasons recorded in both findings
documents:

```sh
JAVA_HOME=/path/to/jdk ./gradlew connectedDebugAndroidTest
```

The Kotlin compiler dex archive built by `tools/kotlinc/build-shim.sh` and
`tools/kotlinc/build-kotlinc-dex.py` is ~100 MB and is not tracked in git; see
[`tools/kotlinc/FINDINGS.md`](tools/kotlinc/FINDINGS.md) for the jar set it
needs. The engine will consume it when Kotlin support lands (M4).

## Licence

GPL-3.0-or-later. See [`LICENSE`](LICENSE).

Copyleft is deliberate. This project exists because the incumbent on-device IDE
went unmaintained behind a paywall and the leading open-source alternative was
archived; the licence is meant to keep that from happening to this one. It also
preserves the option of reusing code from AndroidIDE and its community fork,
which are GPL-3.0.

Third-party components keep their own licences — notably `aapt2` (Apache-2.0,
from AOSP) and the Kotlin compiler (Apache-2.0). `sora-editor` (LGPL-2.1) is
consumed unmodified as a Maven dependency when the editor lands.
