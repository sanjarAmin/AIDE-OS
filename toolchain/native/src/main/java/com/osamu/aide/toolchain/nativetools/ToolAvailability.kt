package com.osamu.aide.toolchain.nativetools

import java.io.File

/**
 * Why a bundled tool can or cannot be used on this device.
 *
 * Modelled explicitly rather than as a nullable [File] so the UI can explain
 * the difference between "your Android version is too old" (permanent, and the
 * user's device is simply out of scope) and "the binary is missing" (a broken
 * install, which is our bug).
 */
sealed interface ToolAvailability {

    data class Available(val executable: File) : ToolAvailability

    /** The device predates the platform APIs the tool was built against. */
    data class UnsupportedApiLevel(
        val tool: NativeTool,
        val required: Int,
        val actual: Int,
    ) : ToolAvailability

    /** Not packaged for this ABI, or stripped by an APK optimiser. */
    data class Missing(val tool: NativeTool, val expectedAt: File) : ToolAvailability

    /**
     * Present but not executable. Usually means the APK was installed with
     * `extractNativeLibs=false`, leaving the file mapped inside the archive.
     */
    data class NotExecutable(val tool: NativeTool, val path: File) : ToolAvailability
}
