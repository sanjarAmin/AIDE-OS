package com.osamu.aide.toolchain.nativetools

import android.os.Build

/**
 * An executable shipped inside the APK's native library directory.
 *
 * Bundled tools are named `lib<name>.so` even though they are executables, not
 * shared libraries: only files matching that pattern are packaged into
 * `jniLibs` and extracted to disk at install time, and since API 29 the native
 * library directory is the only place an app may execute a file from.
 */
enum class NativeTool(
    val libraryName: String,
    val minApiLevel: Int,
) {
    /**
     * Resource compiler and linker.
     *
     * Requires API 30. AOSP's libbase calls `__android_log_set_logger` and
     * `android_fdsan_*` unconditionally, so the binary cannot be built against
     * an older platform -- see tools/aapt2/FINDINGS.md. This is a property of
     * the AOSP sources, not of how we build them.
     */
    AAPT2("libaapt2.so", Build.VERSION_CODES.R),
}
