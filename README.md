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

Early. The shell and the toolchain groundwork exist; the build engine does not
yet. Both of the plan's blocking risks have been closed by working spikes:

| | |
|---|---|
| **aapt2 on-device** | Builds from AOSP for arm64-v8a and x86_64, runs from `nativeLibraryDir`. [Findings](tools/aapt2/FINDINGS.md) |
| **kotlinc + Compose on ART** | The Kotlin compiler and the Compose compiler plugin run on ART and produce transformed bytecode. [Findings](tools/kotlinc/FINDINGS.md) |

Next milestone is **M2**: a Java hello-world project that builds and installs in
under ten seconds.

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

The Kotlin compiler archive that `:spike:kotlinc` loads is ~100 MB and is not
tracked in git. `tools/kotlinc/build-shim.sh` and
`tools/kotlinc/build-kotlinc-dex.py` rebuild it; see
[`tools/kotlinc/FINDINGS.md`](tools/kotlinc/FINDINGS.md) for the jar set it
needs.

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
