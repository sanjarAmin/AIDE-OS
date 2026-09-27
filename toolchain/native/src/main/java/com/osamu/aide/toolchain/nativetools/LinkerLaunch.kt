package com.osamu.aide.toolchain.nativetools

import android.os.Build
import java.io.File

/**
 * Starts a binary that lives in app-private storage.
 *
 * **The app may not execute anything it downloaded.** `execve` is refused
 * everywhere under `/data/data`, and on external storage even a `PROT_EXEC`
 * mapping is refused, so a downloaded toolchain cannot live beside the
 * projects. What is permitted is asking the platform's dynamic linker -- which
 * *is* in an executable location -- to load and start the file for us:
 *
 * ```
 * /system/bin/linker64 /data/data/<pkg>/files/toolchain/bin/clang-21 --version
 * ```
 *
 * Established by spike R9 (`tools/nativeexec/FINDINGS.md`) and exercised with a
 * real toolchain by spike R10 (`tools/clang/FINDINGS.md`), including on Android
 * 16 / API 36, where it still works.
 *
 * **Two things this route costs**, both of which callers have to know about:
 * the launched program sees the *linker's* path in `/proc/self/exe`, so
 * anything that locates its own resources that way is misled; and it cannot
 * spawn helper binaries of its own, because those would have to `execve` out of
 * app storage. `ClangToolchain` is where the consequences are handled.
 *
 * Verify none of this through `adb shell run-as`. That runs in `runas_app`,
 * which *is* allowed to execute from app storage, so it will contradict every
 * sentence above. `tools/clang/FINDINGS.md` §7.
 */
class LinkerLaunch(private val linker: File) {

    /** True when the linker this was built for is actually present. */
    val isAvailable: Boolean get() = linker.canExecute()

    /**
     * Plans a run of [executable] with [arguments].
     *
     * [libraryPath] becomes `LD_LIBRARY_PATH`, which is not optional for a
     * relocated toolchain: the binaries' own `RUNPATH` points at wherever they
     * were packaged for, a prefix this app neither has nor can create. Getting
     * it wrong surfaces as `library "libz.so.1" not found`, which reads like a
     * missing file rather than a path that was never searched.
     */
    fun plan(
        executable: File,
        arguments: List<String> = emptyList(),
        libraryPath: List<File> = emptyList(),
        environment: Map<String, String> = emptyMap(),
    ): LaunchPlan = LaunchPlan(
        command = listOf(linker.absolutePath, executable.absolutePath) + arguments,
        environment = buildMap {
            if (libraryPath.isNotEmpty()) {
                put(LD_LIBRARY_PATH, libraryPath.joinToString(":") { it.absolutePath })
            }
            putAll(environment)
        },
    )

    companion object {
        const val LD_LIBRARY_PATH = "LD_LIBRARY_PATH"

        /** 64-bit binaries need the 64-bit linker; they are not interchangeable. */
        const val LINKER_64 = "/system/bin/linker64"
        const val LINKER_32 = "/system/bin/linker"

        /**
         * The linker matching the ABI this process is running under.
         *
         * Chosen from the process's own ABI rather than the device's supported
         * list: a 64-bit device runs 32-bit apps, and such an app's downloaded
         * toolchain is 32-bit too.
         */
        fun forThisProcess(): LinkerLaunch = LinkerLaunch(
            File(if (Build.SUPPORTED_64_BIT_ABIS.contains(Build.SUPPORTED_ABIS.first())) LINKER_64 else LINKER_32),
        )
    }
}
