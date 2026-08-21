package com.osamu.aide.toolchain.nativetools

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Locates the native executables bundled with the app.
 *
 * [sdkInt] and [nativeLibraryDir] are constructor parameters rather than read
 * from a [Context] internally so the API gating can be unit tested off-device;
 * use [from] for the real thing.
 */
class NativeToolchain(
    private val nativeLibraryDir: File,
    private val sdkInt: Int,
) {

    fun locate(tool: NativeTool): ToolAvailability {
        if (sdkInt < tool.minApiLevel) {
            return ToolAvailability.UnsupportedApiLevel(tool, tool.minApiLevel, sdkInt)
        }

        val executable = File(nativeLibraryDir, tool.libraryName)
        return when {
            !executable.exists() -> ToolAvailability.Missing(tool, executable)
            !executable.canExecute() -> ToolAvailability.NotExecutable(tool, executable)
            else -> ToolAvailability.Available(executable)
        }
    }

    fun isAvailable(tool: NativeTool): Boolean = locate(tool) is ToolAvailability.Available

    companion object {
        fun from(context: Context): NativeToolchain = NativeToolchain(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}
