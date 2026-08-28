package com.osamu.aide.toolchain.nativetools

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import java.io.File

/** Which driver runs, and therefore which language is compiled. */
enum class NativeLanguage { C, CXX }

/**
 * A downloaded clang, made usable.
 *
 * clang runs on a device, but only through [LinkerLaunch], and that launch
 * breaks two things it relies on. This class is where both are handled, so that
 * nothing above it has to know:
 *
 *  1. **It cannot find its own installation.** clang locates its builtin
 *     headers and tools relative to `/proc/self/exe`, which under this launch
 *     is the linker -- so it looks under `/apex/com.android.runtime`, which
 *     holds none of them. [relocationFlags] replaces every path it would have
 *     derived. Omitting one produces a missing-header error that reads like a
 *     broken sysroot rather than a broken launch, which is why they are applied
 *     here and not left to callers.
 *  2. **It cannot start any program.** A tool clang spawns would have to
 *     `execve` out of app storage, which is refused. That rules out compiling
 *     and linking in one invocation -- two jobs means a separate `cc1` -- and
 *     it rules out linking at all, since the driver always spawns `ld.lld`.
 *     Hence [compile] does one job, and [link] asks the driver to *plan* the
 *     link and runs the linker itself.
 *
 * All of it is measured in `tools/clang/FINDINGS.md`, on API 34 x86_64 and
 * API 36 arm64.
 */
class ClangToolchain(
    private val root: File,
    private val abi: String,
    private val launch: LinkerLaunch,
    private val runner: NativeToolRunner,
) {

    private val binaries = File(root, "bin")
    private val libraries = File(root, "lib")

    /**
     * Where clang's builtin headers really are, discovered rather than assumed.
     *
     * The directory is named for the major version (`lib/clang/21`), so
     * hardcoding it would silently break on the next toolchain.
     *
     * **Deduplicated by canonical path**, because the toolchain ships a
     * `latest -> 21` symlink beside the real directory and both answer
     * `isDirectory`. Taking either is correct; seeing two and refusing was the
     * first attempt, and it made a complete install look broken.
     *
     * If there really are two versions this still returns null rather than
     * picking one, because guessing wrong reappears as a missing-header error
     * a long way from here.
     */
    private val resourceDir: File?
        get() = File(libraries, "clang").listFiles()
            ?.filter { it.isDirectory }
            ?.distinctBy { it.canonicalFile }
            ?.singleOrNull()

    /** The target triple for [abi], as the sysroot spells it. */
    val triple: String? = TRIPLES[abi]

    /**
     * Whether this install can actually be used.
     *
     * Checks the pieces a build reaches for, not merely that a directory
     * exists: a partial unpack is a real failure mode for a 550 MB archive,
     * and it presents as a compile error deep in a header.
     */
    val isInstalled: Boolean
        get() = triple != null &&
            resourceDir != null &&
            driver(NativeLanguage.C).canRead() &&
            File(binaries, LINKER).canRead() &&
            File(root, "include").isDirectory

    /**
     * The driver for [language].
     *
     * `clang` and `clang++` are the same binary; the basename it is invoked
     * under is the whole of what selects the language, the standard library and
     * `-lc++_shared`. No flag does this, so the C++ path depends on a symlink
     * surviving installation -- which is why the toolchain is unpacked from a
     * tar rather than a zip.
     *
     * The unversioned names are used deliberately: `clang-21` would pin this
     * class to a toolchain version for no benefit.
     */
    private fun driver(language: NativeLanguage): File = File(
        binaries,
        if (language == NativeLanguage.CXX) "clang++" else "clang",
    )

    /**
     * The flags that replace everything the launch stopped clang deriving.
     *
     * The last two exist because Termux's prefix has no `usr/` level, and for
     * an Android target clang expects `<sysroot>/usr/include`. So the
     * per-triple headers -- `asm/` above all -- and the libc++ headers are both
     * present and both unfound unless named.
     */
    fun relocationFlags(language: NativeLanguage): List<String> = buildList {
        add("-resource-dir"); add(resourceDir!!.absolutePath)
        add("-B"); add(binaries.absolutePath)
        add("--sysroot=${root.absolutePath}")
        add("-I"); add(File(root, "include/$triple").absolutePath)
        if (language == NativeLanguage.CXX) {
            // -cxx-isystem, not -I: libc++'s own warnings are not the user's.
            add("-cxx-isystem"); add(File(root, "include/c++/v1").absolutePath)
        }
    }

    /**
     * Compiles one source to one object. **One job, deliberately.**
     *
     * Producing anything but an object here would give the driver a second job
     * and make it spawn `cc1` through `/proc/self/exe`, which fails with
     * `expected absolute path: "-cc1"`. `-fintegrated-cc1` does not help; it
     * was tried.
     */
    suspend fun compile(
        source: File,
        output: File,
        language: NativeLanguage = NativeLanguage.C,
        arguments: List<String> = emptyList(),
        workingDir: File? = null,
        onLine: (ToolLine) -> Unit = {},
    ): AppResult<ToolResult> {
        val unusable = unusableReason()
        if (unusable != null) return AppResult.Failure(AppError(unusable))

        return runner.run(
            plan = plan(
                driver(language),
                relocationFlags(language) + arguments +
                    listOf("-c", source.absolutePath, "-o", output.absolutePath),
            ),
            workingDir = workingDir,
            describedAs = "clang",
            onLine = onLine,
        )
    }

    /**
     * Links objects into a shared library, by asking clang what it *would* run.
     *
     * `-###` prints the driver's plan and executes nothing, so it survives the
     * restriction that stops the driver linking. The last line is the whole
     * `ld.lld` invocation -- every `crtbegin_so.o`, `libclang_rt.builtins`,
     * `-l:libunwind.a` and search path the platform wants, worked out by the
     * driver. We then run that ourselves through the launch that starts clang.
     *
     * The alternative is composing the linker command here, which would be a
     * copy of clang's per-target logic and would go stale the first time the
     * toolchain moves.
     */
    suspend fun link(
        objects: List<File>,
        output: File,
        language: NativeLanguage = NativeLanguage.C,
        arguments: List<String> = emptyList(),
        workingDir: File? = null,
        onLine: (ToolLine) -> Unit = {},
    ): AppResult<ToolResult> {
        val unusable = unusableReason()
        if (unusable != null) return AppResult.Failure(AppError(unusable))
        if (objects.isEmpty()) return AppResult.Failure(AppError("nothing to link"))

        val plan = StringBuilder()
        val planned = runner.run(
            plan = plan(
                driver(language),
                relocationFlags(language) + arguments + listOf("-shared") +
                    objects.map { it.absolutePath } +
                    listOf("-o", output.absolutePath, "-###"),
            ),
            workingDir = workingDir,
            describedAs = "clang",
        ) { line -> plan.appendLine(line.text) }

        when (planned) {
            is AppResult.Failure -> return planned
            is AppResult.Success ->
                if (!planned.value.isSuccess) {
                    // The driver's own diagnostics are the useful thing here,
                    // and -### wrote them to the buffer rather than to onLine.
                    plan.lines().forEach { onLine(ToolLine(ToolStream.STDERR, it)) }
                    return planned
                }
        }

        val command = readLinkerCommand(plan.toString())
            ?: return AppResult.Failure(
                AppError("clang did not plan a link that could be read:\n$plan"),
            )

        // The linker is started exactly the way clang was: it is a downloaded
        // binary in app storage too, and nothing there runs by itself.
        return runner.run(
            plan = plan(File(command.first()), command.drop(1)),
            workingDir = workingDir,
            describedAs = LINKER,
            onLine = onLine,
        )
    }

    /**
     * Pulls the linker invocation out of a `-###` transcript.
     *
     * The driver quotes every token precisely so it can be read back. Only the
     * last line is a command -- the ones before are the version banner -- and
     * it is required to be the linker, so that a transcript whose shape has
     * changed fails loudly instead of executing whatever its last line held.
     */
    internal fun readLinkerCommand(transcript: String): List<String>? {
        val last = transcript.trim().lines().lastOrNull()?.trim() ?: return null
        val arguments = QUOTED.findAll(last).map { it.groupValues[1] }.toList()
        return arguments.takeIf { it.firstOrNull()?.endsWith(LINKER) == true }
    }

    private fun plan(executable: File, arguments: List<String>): LaunchPlan =
        launch.plan(
            executable = executable,
            arguments = arguments,
            libraryPath = listOf(libraries),
        )

    private fun unusableReason(): String? = when {
        !launch.isAvailable -> "This device has no dynamic linker at the expected path."
        triple == null -> "The C/C++ toolchain does not support this device's ABI ($abi)."
        !isInstalled -> "The C/C++ toolchain is not installed, or its download did not finish."
        else -> null
    }

    private companion object {
        const val LINKER = "ld.lld"

        /** One `"…"` token of a `-###` plan. */
        val QUOTED = Regex("\"([^\"]*)\"")

        /** Only the 64-bit ABIs: the toolchain is not built for the others. */
        val TRIPLES = mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "x86_64" to "x86_64-linux-android",
        )
    }
}
