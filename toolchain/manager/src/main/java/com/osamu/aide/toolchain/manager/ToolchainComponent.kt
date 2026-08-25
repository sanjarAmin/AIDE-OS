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
    /**
     * The entries to keep, each mapped to the name it takes once installed.
     * Everything else in the archive is discarded.
     *
     * A map rather than a single entry because components differ in shape, not
     * because it is more general: the Android platform wants one file out of a
     * 63 MB zip, and the Kotlin compiler wants two out of its own archive. Two
     * components for one logical install would mean showing a user two download
     * prompts for one feature.
     */
    val entries: Map<String, String>,
    val requiresSdkLicense: Boolean = true,
) {
    /**
     * The file a caller means when it asks for "the" component.
     *
     * The first entry, which is why [entries] is written in a deliberate order:
     * for Kotlin that is the compiler archive, not the stdlib beside it.
     */
    val primaryInstalledName: String get() = entries.values.first()

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
            entries = mapOf("android-36/android.jar" to "android.jar"),
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
            entries = mapOf(
                "kotlinc.jar" to "kotlinc.jar",
                "kotlin-stdlib.jar" to "kotlin-stdlib.jar",
            ),
            requiresSdkLicense = false,
        )
    }
}
