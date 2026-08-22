package com.osamu.aide.toolchain.manager

import java.io.File

/**
 * How an install is going.
 *
 * Reported as a stream rather than returned, because the thing being reported
 * is a 63 MB download on a phone: a progress bar is the whole user experience,
 * and a result that only arrives at the end is indistinguishable from a hang.
 */
sealed interface InstallProgress {

    data class Downloading(val bytes: Long, val totalBytes: Long) : InstallProgress {
        /** 0f..1f, or null when the server did not say how large the file is. */
        val fraction: Float? get() = if (totalBytes > 0) bytes.toFloat() / totalBytes else null
    }

    /** Checksumming 63 MB is not instant, and a silent pause here looks broken. */
    data object Verifying : InstallProgress

    data object Extracting : InstallProgress

    data class Installed(val file: File) : InstallProgress

    /**
     * [licenseRequired] separates the one failure the user can fix by agreeing
     * to something from every failure they cannot, so the UI can offer the
     * licence rather than an error.
     */
    data class Failed(
        val message: String,
        val licenseRequired: Boolean = false,
    ) : InstallProgress
}
