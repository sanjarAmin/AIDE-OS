package com.osamu.aide.toolchain.manager

import java.io.File

/**
 * Records that the user accepted the Android SDK Terms and Conditions.
 *
 * Downloading a platform from Google's repository is only permitted under that
 * agreement, so acceptance is a precondition of installing rather than an
 * onboarding screen that can be skipped: [ComponentInstaller] refuses without
 * it. The text is Google's, shipped verbatim as `R.raw.android_sdk_license`.
 *
 * Kept as a file rather than a preference so that it is unambiguous which
 * install it governs -- it lives beside the components it permitted, and
 * clearing app data clears both together.
 */
class SdkLicense(root: File) {

    private val marker = File(root, ACCEPTED_MARKER)

    fun isAccepted(): Boolean = marker.isFile

    /** [at] is recorded so the app can say what was agreed to and when. */
    fun accept(at: Long = System.currentTimeMillis()) {
        marker.parentFile?.mkdirs()
        marker.writeText("$LICENSE_ID\n$at\n")
    }

    fun revoke() {
        marker.delete()
    }

    /** When it was accepted, or null if it has not been. */
    fun acceptedAt(): Long? = marker
        .takeIf { it.isFile }
        ?.runCatching { readLines().getOrNull(1)?.trim()?.toLong() }
        ?.getOrNull()

    private companion object {
        const val ACCEPTED_MARKER = "android-sdk-license.accepted"

        /** Google's own id for the agreement, from the repository index. */
        const val LICENSE_ID = "android-sdk-license"
    }
}
