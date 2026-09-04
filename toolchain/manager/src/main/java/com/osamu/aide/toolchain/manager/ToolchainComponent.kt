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
            archiveSha1 = "9578660382dfe1ce658fb43dd5c20e9061dbc2e2",
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
    }
}
