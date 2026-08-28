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
