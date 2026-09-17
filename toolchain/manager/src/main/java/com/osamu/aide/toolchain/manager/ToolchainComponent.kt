package com.osamu.aide.toolchain.manager

/**
 * A piece of toolchain too large to ship inside the APK.
 *
 * Every field but [displayName] is part of a pin. The archive is identified by
 * an exact URL and an exact SHA-1, not by a query against Google's repository
 * index: an index is a moving target, and a build engine whose compile
 * classpath silently changes under it is a support problem nobody can debug.
 * Moving to a new platform is a code change with a diff, which is the point.
 *
 * The checksum is SHA-1 because that is what Google's repository publishes. It
 * is not being relied on for authenticity -- HTTPS to dl.google.com does that --
 * only to catch a corrupt or truncated download, which is what it is good for.
 */
data class ToolchainComponent(
    val id: String,
    val displayName: String,
    val archiveUrl: String,
    val archiveSha1: String,
    val archiveBytes: Long,
    /** What the archive holds, and what installing it means. */
    val archive: ComponentArchive,
    /**
     * Roughly what the install occupies once unpacked.
     *
     * Carried rather than inferred from [archiveBytes]. For a zip of which two
     * files are kept the installed size is smaller than the download; for a
     * gzipped tree it is three and a half times larger, and guessing either way
     * means either refusing an install that would have fitted or starting one
     * that fills the device.
     */
    val installedBytes: Long,
    val requiresSdkLicense: Boolean = true,
    /**
     * A SHA-256 pin, checked instead of [archiveSha1] when present.
     *
     * For artifacts whose publisher gives SHA-256 and nothing else. Hugging
     * Face publishes a file's SHA-256 as its LFS object id; pinning a model by
     * SHA-1 would mean downloading gigabytes to a laptop just to compute a
     * weaker digest of a file whose stronger one is already on the page.
     */
    val archiveSha256: String? = null,
) {
    /** The file a caller means when it asks for "the" component. */
    val primaryInstalledName: String get() = archive.installedMarker

    companion object {

        /**
         * The platform every project is compiled and linked against.
         *
         * API 36 rather than the newest: a platform the fast engine compiles
         * against has to be one aapt2 can also link against, and both halves
         * moving at once is a change worth making deliberately.
         *
         * The archive is 63 MB and only `android.jar` -- 26 MB of it -- is
         * kept. Fetching just that entry with range requests would save the
         * difference and is deliberately not done: the published checksum
         * covers the whole archive, so verifying it at all means having all of
         * it. A corrupt android.jar surfaces as inexplicable compile errors in
         * the user's own code, which is much worse than 37 MB of transfer.
         */
        val ANDROID_PLATFORM = ToolchainComponent(
            id = "platforms;android-36",
            displayName = "Android SDK Platform 36",
            archiveUrl = "https://dl.google.com/android/repository/platform-36_r02.zip",
            archiveSha1 = "2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576",
            archiveBytes = 65_878_410L,
            archive = ComponentArchive.ZipEntries(
                mapOf("android-36/android.jar" to "android.jar"),
            ),
            installedBytes = 27_000_000L,
        )

        /**
         * Build-tools, which AGP requires to exist and barely uses.
         *
         * Needed only by `:engine:gradle`. The fast engine ships its own aapt2
         * and calls D8 and apksig as libraries, so it never looks at this;
         * AGP, running a real Gradle build, validates the SDK's `build-tools`
         * directory before it will compile anything and refuses with "Installed
         * Build Tools revision 36.0.0 is corrupted" if it is not to its liking.
         *
         * **Almost none of it can run here.** `aapt2`, `zipalign`, `aidl`,
         * `aapt` and `dexdump` are x86_64 glibc ELF — wrong architecture on a
         * phone and wrong libc on the emulator — and `d8` and `apksigner` are
         * `#!/bin/bash` wrappers, on a system whose shell is `sh`. What AGP
         * actually executes is our aapt2, pointed at by
         * `android.aapt2FromMavenOverride`; what it dexes and signs with are
         * R8 and apksig resolved from Maven and run in its own JVM. So this is
         * downloaded to be *looked at*, which is a strange thing to spend 60 MB
         * on and worth stating plainly.
         *
         * The three excluded directories are RenderScript's toolchain, dead
         * since Android 12 and 111 MB of the 147 installed. Removing them was
         * verified by building with them absent. Trimming further — to only the
         * tools AGP runs — was also tried, and **fails**: the directory is
         * checked for completeness, not for usefulness.
         *
         * The root is renamed because Google names it for the platform
         * codename, `android-16`, while AGP looks it up by revision.
         */
        val ANDROID_BUILD_TOOLS = ToolchainComponent(
            id = "build-tools;36.0.0",
            displayName = "Android SDK Build-Tools 36",
            archiveUrl = "https://dl.google.com/android/repository/build-tools_r36_linux.zip",
            archiveSha1 = "b0b6376977657e8ad9b969bacf4093601da2c6fb",
            archiveBytes = 63_737_259L,
            archive = ComponentArchive.ZipTree(
                installedMarker = "$BUILD_TOOLS_REVISION/source.properties",
                renameRoot = "android-16" to BUILD_TOOLS_REVISION,
                exclude = setOf("lib64", "lld-bin", "renderscript"),
            ),
            installedBytes = 38_000_000L,
        )

        /** The revision AGP looks build-tools up by, and the directory's name. */
        const val BUILD_TOOLS_REVISION = "36.0.0"

        /**
         * The Kotlin compiler and the Compose plugin, dexed to run on ART.
         *
         * Unlike the platform, this is **ours**: built by
         * `tools/kotlinc/build-kotlinc-dex.py` and published on this project's
         * own releases, because nothing upstream ships a Kotlin compiler that
         * runs on Android. The pin is the release tag, so the archive behind
         * this URL cannot change under a user.
         *
         * No SDK licence: Kotlin is Apache-2.0 and none of Google's terms
         * apply. The platform's prompt would be a lie here.
         */
        val KOTLIN_COMPILER = ToolchainComponent(
            id = "kotlin-compiler",
            displayName = "Kotlin compiler 2.2.10",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "kotlinc-2.2.10/kotlin-compiler-2.2.10.zip",
            archiveSha1 = "b374ecd0b4ca40264df70c3714d8956349063a4b",
            archiveBytes = 56_196_599L,
            // Both are needed: the compiler cannot start without a stdlib to
            // put in the kotlin-home it is given. See tools/kotlinc/FINDINGS.md.
            archive = ComponentArchive.ZipEntries(
                mapOf(
                    "kotlinc.jar" to "kotlinc.jar",
                    "kotlin-stdlib.jar" to "kotlin-stdlib.jar",
                ),
            ),
            installedBytes = 57_000_000L,
            requiresSdkLicense = false,
        )

        /**
         * The Kotlin Analysis API, for Kotlin intelligence.
         *
         * Ours, like the compiler beside it: JetBrains publishes these jars
         * only to their own repository, they are built against the *unshaded*
         * IntelliJ namespace that `kotlin-compiler-embeddable` shades, and
         * nothing upstream ships either one dexed. `tools/analysisapi/` fetches
         * them from a pinned lock, relocates 9204 references onto the
         * compiler's namespace, dexes the result and packages it here.
         *
         * **Two archives in one component, and neither works alone.**
         * `analysis-api.jar` is the API; `analysis-backend.jar` is the code
         * that drives it, compiled against the relocated jars because nothing
         * in the app can name a type from behind that classloader. They load on
         * one flat loader together. Shipping them apart would let a device hold
         * one and not the other, which fails as a ClassNotFoundException for a
         * class that is simply in the archive nobody downloaded.
         *
         * Requires [KOTLIN_COMPILER] to be installed as well -- the API is
         * built against the compiler's shaded IntelliJ and cannot load without
         * it. `KotlinArchives` takes both.
         *
         * No SDK licence: Apache-2.0, and none of Google's terms apply.
         */
        val KOTLIN_ANALYSIS_API = ToolchainComponent(
            id = "kotlin-analysis-api",
            displayName = "Kotlin Analysis API 2.2.10",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "kotlin-analysis-2.2.10/kotlin-analysis-2.2.10.zip",
            archiveSha1 = "9d1d1ae724af7afa4de325b622c5a806294d29eb",
            archiveBytes = 1_991_075L,
            archive = ComponentArchive.ZipEntries(
                mapOf(
                    "analysis-api.jar" to "analysis-api.jar",
                    "analysis-backend.jar" to "analysis-backend.jar",
                ),
            ),
            installedBytes = 2_100_000L,
            requiresSdkLicense = false,
        )

        /**
         * A JVM, for the Gradle build path.
         *
         * Ours, for the reason the Kotlin compiler and clang are: nothing
         * upstream ships a JDK that runs on Android. Termux builds OpenJDK
         * against Bionic, so `bin/java` is an ordinary Android ELF whose
         * interpreter is `/system/bin/linker64` — the one shape this app can
         * start. `tools/rootfs/fetch-jvm.sh` assembles it.
         *
         * **Three of its binaries have to be replaced before it works**, all
         * because an app may execute only what is in `nativeLibraryDir`:
         * `bin/java` re-execs itself and dies, the tools a build execs cannot
         * start, and `lib/jspawnhelper` is what the JVM uses to spawn anything
         * at all. `JvmToolchain.prepare()` does it; installing this component
         * without that leaves a JDK that looks fine and runs nothing.
         *
         * No SDK licence: OpenJDK is GPLv2 with the Classpath Exception.
         */
        fun openJdk(abi: String): ToolchainComponent? = when (abi) {
            "arm64-v8a" -> openJdk(
                architecture = "aarch64",
                sha1 = "59a24c994952ec5f391e0779260a6e4d5e114c13",
                archiveBytes = 148_583_010L,
                installedBytes = 303_000_000L,
            )
            "x86_64" -> openJdk(
                architecture = "x86_64",
                sha1 = "e4552933358bfd47de40c819cc1071004f86c7dd",
                archiveBytes = 149_797_431L,
                installedBytes = 305_000_000L,
            )
            // Not built for the 32-bit ABIs. Null rather than a component that
            // cannot install, so the caller says "not on this device" instead
            // of failing mid-download.
            else -> null
        }

        private fun openJdk(
            architecture: String,
            sha1: String,
            archiveBytes: Long,
            installedBytes: Long,
        ) = ToolchainComponent(
            // Matches GradleToolchainProvider.JDK_COMPONENT_ID; a test checks.
            // No ABI in the id, unlike clang's: a device runs one architecture
            // and the engine looks the JDK up by this name alone.
            id = "openjdk-21",
            displayName = "Java runtime (OpenJDK 21.0.12)",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "openjdk-21.0.12/openjdk-21.0.12-$architecture.tar.gz",
            archiveSha1 = sha1,
            archiveBytes = archiveBytes,
            // The JDK directory carries its version, so the marker names what
            // cannot move: the server VM, without which nothing starts.
            archive = ComponentArchive.GzippedTar("lib/jvm/java-21-openjdk/lib/server/libjvm.so"),
            installedBytes = installedBytes,
            requiresSdkLicense = false,
        )

        /**
         * Gradle itself, from Gradle.
         *
         * **Not re-hosted.** Unlike the JDK, the compiler and clang, this runs
         * unmodified on any JVM and its publisher ships checksummed
         * distributions; copying it onto this project's releases would add a
         * second thing to keep current and nothing else. The pin is the version
         * in the URL.
         *
         * The SHA-1 here was taken from the file after checking it against
         * Gradle's own published SHA-256 — so the pin is anchored to what the
         * publisher signed, not merely to what a download once returned.
         */
        val GRADLE = ToolchainComponent(
            // Matches GradleToolchainProvider.GRADLE_COMPONENT_ID.
            id = "gradle",
            displayName = "Gradle 9.7.1",
            archiveUrl = "https://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
            archiveSha1 = "9291eadd0d5f2122ff70115d5abfea4c60cdb7f5",
            archiveBytes = 151_433_392L,
            archive = ComponentArchive.ZipTree("gradle-9.7.1/lib/gradle-launcher-9.7.1.jar"),
            installedBytes = 175_000_000L,
            requiresSdkLicense = false,
        )

        /**
         * clang and lld, for building native code on the device.
         *
         * Ours, like the Kotlin compiler and for the same reason: nothing
         * upstream ships a clang that runs on Android. Assembled by
         * `tools/clang/fetch-toolchain.sh` from Termux's packages, which are
         * built for Bionic and link as PIE -- the NDK's clang targets glibc
         * hosts and cannot start on a device at all.
         *
         * Kept whole rather than reduced to a few entries. 551 MB is a great
         * deal to ask, and roughly 115 MB of it is LLVM tools a compile never
         * touches; trimming is a real opportunity and is deliberately not taken
         * blind, because `llvm-ar` and `llvm-strip` are wanted the moment
         * static libraries are.
         *
         * No SDK licence: LLVM is Apache-2.0 with the LLVM exception, and
         * Google's terms have nothing to do with it.
         */
        fun nativeToolchain(abi: String): ToolchainComponent? = when (abi) {
            "arm64-v8a" -> nativeToolchain(
                abi = abi,
                architecture = "aarch64",
                sha1 = "17ffea7d5fd511e5d8ce2bc853d05aae5f29922d",
                archiveBytes = 159_090_036L,
                installedBytes = 551_000_000L,
            )
            "x86_64" -> nativeToolchain(
                abi = abi,
                architecture = "x86_64",
                sha1 = "008cc6f6fce539eb3472d7521c4dbc771dfda613",
                archiveBytes = 162_163_743L,
                installedBytes = 600_000_000L,
            )
            // The toolchain is not built for the 32-bit ABIs. Returning null
            // rather than a component that cannot install lets the caller say
            // "not on this device" instead of failing mid-download.
            else -> null
        }

        private fun nativeToolchain(
            abi: String,
            architecture: String,
            sha1: String,
            archiveBytes: Long,
            installedBytes: Long,
        ) = ToolchainComponent(
            // The ABI is in the id, so two of them can be installed side by
            // side and neither is mistaken for the other.
            id = "clang-21.1.8-$abi",
            displayName = "C/C++ toolchain (clang 21.1.8)",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "clang-21.1.8/clang-21.1.8-$architecture.tar.gz",
            archiveSha1 = sha1,
            archiveBytes = archiveBytes,
            // `usr/bin/clang` is a symlink; it resolves once the tree is whole,
            // which is exactly the condition worth testing for.
            archive = ComponentArchive.GzippedTar("usr/bin/clang"),
            installedBytes = installedBytes,
            requiresSdkLicense = false,
        )

        /**
         * Node.js, for M10's JavaScript half.
         *
         * Termux's build, because the ones from nodejs.org are glibc and cannot
         * start here at all -- the same reason the JDK and clang are re-hosted.
         * Carries npm, which is a *separate* Termux package: an archive built
         * from the runtime alone has a `bin/` holding `node` and `corepack` and
         * nothing else. `tools/node/FINDINGS.md`, spike R13.
         *
         * No SDK licence: MIT.
         */
        fun node(abi: String): ToolchainComponent? = when (abi) {
            "arm64-v8a" -> node(
                architecture = "aarch64",
                sha1 = "4407a32a6e6e7d7e0f0a51a8d27a68381fddcbf0",
                archiveBytes = 38_086_286L,
            )
            "x86_64" -> node(
                architecture = "x86_64",
                sha1 = "bc37e23c37588b558ab25663e51efb35e053b5c4",
                archiveBytes = 37_312_652L,
            )
            // Not built for the 32-bit ABIs, for the reason the JDK is not.
            else -> null
        }

        private fun node(
            architecture: String,
            sha1: String,
            archiveBytes: Long,
        ) = ToolchainComponent(
            id = "node-24",
            displayName = "Node.js 24.18.0",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "node-24.18.0/node-24.18.0-$architecture.tar.gz",
            archiveSha1 = sha1,
            archiveBytes = archiveBytes,
            // The runtime itself. npm is JavaScript and would still be there
            // with a broken binary, so it is the wrong thing to check for.
            archive = ComponentArchive.GzippedTar("bin/node"),
            installedBytes = 110_000_000L,
            requiresSdkLicense = false,
        )

        /**
         * Mono, for M10's C# half.
         *
         * **Not the .NET SDK**, which Termux does not publish and Microsoft
         * ships only for glibc. `tools/mono/FINDINGS.md`, spike R14 -- which
         * also records the two things a consumer has to do that no other
         * component needs: run the compiler as `lib/mono/4.5/mcs.exe` rather
         * than through the `bin/mcs` shell script, and rewrite `$mono_libdir`
         * in the shipped config or every `System.IO` call fails.
         * `MonoToolchain` does both.
         *
         * No SDK licence: MIT.
         */
        fun mono(abi: String): ToolchainComponent? = when (abi) {
            "arm64-v8a" -> mono(
                architecture = "aarch64",
                sha1 = "1c879ac363958647eabd9e066b73c9c7f6912cd1",
                archiveBytes = 45_526_315L,
            )
            "x86_64" -> mono(
                architecture = "x86_64",
                sha1 = "9719c1b0054d7c3e490ab646dc80a5a990c95f2e",
                archiveBytes = 45_166_352L,
            )
            else -> null
        }

        private fun mono(
            architecture: String,
            sha1: String,
            archiveBytes: Long,
        ) = ToolchainComponent(
            id = "mono-6",
            displayName = "Mono 6.14.1 (C#)",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "mono-6.14.1/mono-6.14.1-$architecture.tar.gz",
            archiveSha1 = sha1,
            archiveBytes = archiveBytes,
            // `bin/mono` is a symlink to this; naming the symlink would pass
            // for an archive whose target never arrived.
            archive = ComponentArchive.GzippedTar("bin/mono-sgen"),
            installedBytes = 120_000_000L,
            requiresSdkLicense = false,
        )

        /**
         * CPython, for Python projects.
         *
         * Termux's build, for the reason node's and mono's are: python.org
         * publishes no Android build at all, and every manylinux wheel the rest
         * of the ecosystem is made of is glibc. `tools/python/fetch-python.sh`
         * assembles it from the package repo, walking the closure from two
         * roots -- `python` and `python-pip`, which are separate packages, the
         * same trap `nodejs-lts` and `npm` set.
         *
         * **The smallest of the three runtimes by a wide margin**: 13 MB down
         * and 40 MB installed, against node's 38/110 and mono's 45/120. The
         * trim is what makes that true and it is listed in the fetch script --
         * CPython's own test suite is 25 MB of the untrimmed tree.
         *
         * **The pin is reproducible, which no other component's is.**
         * `fetch-python.sh` sorts the tar, zeroes uid/gid and mtimes and gzips
         * with `-n`, so two people running it produce identical bytes --
         * verified by running it twice. That makes this checksum something
         * anyone can re-derive rather than a number only its author can
         * confirm, and it is the shape the other fetch scripts should take.
         *
         * The marker is the versioned binary rather than `bin/python3`, which
         * is a **symlink** to it: an unpack that stopped between the two leaves
         * a link pointing at nothing, and a link that resolves nowhere is
         * `isFile == false` anyway -- so checking the real file is both the
         * earlier and the honest test. The same reasoning as mono's
         * `bin/mono-sgen`, and `ToolchainManager.pythonRoot` states the version
         * once so this and it cannot disagree.
         *
         * PSF licence: no SDK licence to accept.
         */
        fun python(abi: String): ToolchainComponent? = when (abi) {
            "arm64-v8a" -> python(
                architecture = "aarch64",
                sha1 = "b962ba23cc4f1003470758e7a7c17fe08d9b7993",
                archiveBytes = 13_397_153L,
            )
            "x86_64" -> python(
                architecture = "x86_64",
                sha1 = "3d87abc1ac4f689b84630498afbeb5abb7c7ae33",
                archiveBytes = 13_219_055L,
            )
            // Not built for the 32-bit ABIs, for the reason the JDK is not.
            else -> null
        }

        private fun python(
            architecture: String,
            sha1: String,
            archiveBytes: Long,
        ) = ToolchainComponent(
            id = "python-3.14",
            displayName = "Python 3.14.6",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "python-3.14.6/python-3.14.6-$architecture.tar.gz",
            archiveSha1 = sha1,
            archiveBytes = archiveBytes,
            archive = ComponentArchive.GzippedTar(PYTHON_RUNTIME),
            installedBytes = 40_000_000L,
            requiresSdkLicense = false,
        )

        /**
         * The interpreter inside the archive, spelled once.
         *
         * The version is in the binary's own name, so a Termux bump to 3.15
         * changes this line and `PythonToolchain` finds whatever is there by
         * pattern. Two places spelling it differently is an install that
         * verifies and a toolchain that reports itself missing.
         */
        const val PYTHON_RUNTIME = "bin/python3.14"

        /**
         * Every component this app can install.
         *
         * Exists so something can iterate them: `PinnedReleaseTest` checks each
         * pin against what is actually published, which nothing did until a
         * wrong `archiveBytes` shipped and made a component impossible to
         * install. `FINDINGS.md`. Add new components here, or they go
         * unchecked.
         */
        /** The ABIs this project builds toolchains for. */
        /**
         * llama.cpp's server, for the local model benchmark.
         *
         * Termux's build, for the reason Node's is: the upstream binaries are
         * glibc. Trimmed to `llama-server` and its libraries by
         * `tools/localai/fetch-llama.sh`. CPU backend only. MIT, so no SDK
         * licence. Spike R16, `tools/localai/FINDINGS.md`.
         */
        fun llamaCpp(abi: String): ToolchainComponent? = when (abi) {
            "arm64-v8a" -> llamaCpp("aarch64", "7b0fb888873ad57fd22e5cf649a2cacb77956169", 12_591_759L)
            "x86_64" -> llamaCpp("x86_64", "22bfb2f7dbe1b790aff407358909ac0285a1ba56", 12_529_067L)
            else -> null
        }

        private fun llamaCpp(architecture: String, sha1: String, archiveBytes: Long) = ToolchainComponent(
            id = "llama-cpp-0.4.0",
            displayName = "llama.cpp 0.4.0",
            archiveUrl = "https://github.com/sanjarAmin/AIDE-OS/releases/download/" +
                "llama-cpp-0.4.0/llama-cpp-0.4.0-$architecture.tar.gz",
            archiveSha1 = sha1,
            archiveBytes = archiveBytes,
            archive = ComponentArchive.GzippedTar("bin/llama-server"),
            installedBytes = 27_000_000L,
            requiresSdkLicense = false,
        )

        /**
         * Coding models for the local model benchmark, smallest first.
         *
         * From Hugging Face directly, pinned by repository **revision** -- a
         * GGUF re-uploaded under the same name is a different model -- and by
         * the SHA-256 the page publishes. Qwen2.5-Coder, Q4_K_M, because it is
         * the size-for-quality point llama.cpp's own guidance recommends and the
         * one spike R16 measured.
         *
         * The 3B is under Qwen's research licence, which does not allow
         * commercial use: fine for measuring a phone, and a reason it could not
         * be what a shipped feature offers. The others are Apache-2.0.
         */
        val LOCAL_MODELS: List<ToolchainComponent> = listOf(
            model(
                size = "0.5b", label = "Qwen2.5-Coder 0.5B",
                revision = "ebb2015119c907b064c512bf053e945850b5875f",
                bytes = 491_400_064L,
                sha256 = "1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32",
            ),
            model(
                size = "1.5b", label = "Qwen2.5-Coder 1.5B",
                revision = "f86cb2c1fa58255f8052cc32aeede1b7482d4361",
                bytes = 1_117_320_768L,
                sha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
            ),
            model(
                size = "3b", label = "Qwen2.5-Coder 3B (research licence)",
                revision = "f74adce6aa16316c625447af059dbebe4983757c",
                bytes = 2_104_932_800L,
                sha256 = "724fb256bec1ff062b2f65e4569e871ad2e95ab2a3989723d1769c54294730b7",
            ),
            model(
                size = "7b", label = "Qwen2.5-Coder 7B",
                revision = "13fb94bfda8c8cf22497dc57b78f391a9acb426a",
                bytes = 4_683_073_536L,
                sha256 = "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c",
            ),
        )

        private fun model(size: String, label: String, revision: String, bytes: Long, sha256: String): ToolchainComponent {
            val file = "qwen2.5-coder-$size-instruct-q4_k_m.gguf"
            val repo = "Qwen/Qwen2.5-Coder-${size.uppercase()}-Instruct-GGUF"
            return ToolchainComponent(
                id = "model-qwen2.5-coder-$size-q4km",
                displayName = label,
                archiveUrl = "https://huggingface.co/$repo/resolve/$revision/$file",
                archiveSha1 = "",
                archiveSha256 = sha256,
                archiveBytes = bytes,
                archive = ComponentArchive.SingleFile(file),
                installedBytes = bytes,
                requiresSdkLicense = false,
            )
        }

        private val ABIS = listOf("arm64-v8a", "x86_64")

        val ALL: List<ToolchainComponent> = listOfNotNull(
            ANDROID_PLATFORM,
            ANDROID_BUILD_TOOLS,
            KOTLIN_COMPILER,
            KOTLIN_ANALYSIS_API,
            GRADLE,
            // **Both ABIs of every per-architecture component.** These were
            // missing, so the JDK's and clang's pins went unchecked by the very
            // test written after a wrong pin shipped -- and they are the
            // components most likely to drift, being ours and rebuilt by hand.
            *ABIS.flatMap { listOfNotNull(openJdk(it), nativeToolchain(it), node(it), mono(it), python(it), llamaCpp(it)) }
                .toTypedArray(),
            *LOCAL_MODELS.toTypedArray(),
        )
    }
}
