package com.osamu.aide.engine.fast

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.toolchain.nativetools.ClangToolchain
import com.osamu.aide.toolchain.nativetools.NativeLanguage
import com.osamu.aide.toolchain.nativetools.ToolLine
import java.io.File

/**
 * Compiles a project's C and C++ into one shared library.
 *
 * **One clang invocation per source, then a separate link.** Not a style
 * choice: clang cannot compile and link in one go here, because two jobs make
 * the driver spawn `cc1` through `/proc/self/exe`, and it cannot run the linker
 * in any case -- both consequences of the toolchain living in app storage,
 * which nothing may execute out of. [ClangToolchain] handles the mechanics;
 * this stage's job is to give it one job at a time.
 *
 * Everything is built for the device's own ABI and only that one. An on-device
 * IDE's output is almost always installed on the device that built it, and
 * cross-compiling would mean downloading a second 551 MB toolchain to produce
 * a library this device cannot run.
 *
 * `tools/clang/FINDINGS.md` records why each of those is true.
 */
internal class NativeCompileStage(private val clang: ClangToolchain) {

    /**
     * The library and anything that has to travel with it.
     *
     * [runtime] is `libc++_shared.so` when the project used C++, and empty
     * otherwise. It belongs to the toolchain rather than to Android, so nothing
     * on the device resolves it and the APK has to carry it -- which is exactly
     * what the NDK's Gradle plugin does.
     */
    data class NativeOutput(val library: File, val runtime: List<File>) {
        val all: List<File> get() = listOf(library) + runtime
    }

    suspend fun build(
        layout: ProjectLayout,
        workspace: BuildWorkspace,
        libraryName: String,
        onDiagnostic: (Diagnostic) -> Unit,
    ): StageResult<NativeOutput?> {
        val sources = layout.nativeSources()
        // No native code is the common case and costs nothing: the toolchain is
        // never consulted, so a project without C never needs one installed.
        if (sources.isEmpty()) return StageResult.ok(null)

        val output = workspace.nativeDir.apply { mkdirs() }
        val objects = mutableListOf<File>()
        val collected = StringBuilder()
        val report: (ToolLine) -> Unit = { line ->
            collected.appendLine(line.text)
            ClangDiagnostics.parseLine(line.text, layout.root)?.let(onDiagnostic)
        }

        for (source in sources) {
            val objectFile = File(output, source.nameWithoutExtension + ".o")
            val result = clang.compile(
                source = source,
                output = objectFile,
                language = languageOf(source),
                // Position-independent, because the result is a shared library.
                // Without it the link fails with relocation errors that name
                // the object file rather than the missing flag.
                arguments = listOf("-fPIC"),
                workingDir = output,
                onLine = report,
            )
            when (result) {
                is AppResult.Failure -> return StageResult.failed(result.error.message)
                is AppResult.Success ->
                    if (!result.value.isSuccess) {
                        return StageResult.failed(
                            "Compiling ${source.name} failed.",
                        )
                    }
            }
            objects += objectFile
        }

        val usesCxx = sources.any { languageOf(it) == NativeLanguage.CXX }
        val library = File(output, "lib$libraryName.so")
        val linked = clang.link(
            objects = objects,
            output = library,
            // The driver decides from its own name what to link against, so a
            // C++ project must be linked by the C++ driver even though the
            // objects are already compiled. Linking these with the C driver
            // omits libc++ and fails on every symbol the standard library owns.
            language = if (usesCxx) NativeLanguage.CXX else NativeLanguage.C,
            workingDir = output,
            onLine = report,
        )
        when (linked) {
            is AppResult.Failure -> return StageResult.failed(linked.error.message)
            is AppResult.Success ->
                if (!linked.value.isSuccess) return StageResult.failed("Linking $libraryName failed.")
        }

        return StageResult.ok(
            NativeOutput(
                library = library,
                runtime = if (usesCxx) listOfNotNull(clang.cxxRuntime()) else emptyList(),
            ),
        )
    }

    private fun languageOf(source: File): NativeLanguage =
        if (source.extension.lowercase() == "c") NativeLanguage.C else NativeLanguage.CXX
}
