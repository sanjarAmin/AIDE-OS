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
    /** The single entry to keep. The rest of the archive is discarded. */
    val entry: String,
    /** What that entry is called once installed. */
    val installedName: String,
    val requiresSdkLicense: Boolean = true,
) {
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
            entry = "android-36/android.jar",
            installedName = "android.jar",
        )
    }
}
