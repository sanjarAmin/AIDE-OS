package com.osamu.aide.engine.fast

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.hasErrors
import com.osamu.aide.toolchain.nativetools.NativeTool
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.ToolLine
import com.osamu.aide.toolchain.nativetools.ToolResult
import java.io.File

/**
 * The two aapt2 stages: compile resources, then link them into an APK.
 *
 * This is the only part of the pipeline that leaves the JVM. aapt2 is a real
 * process, executed out of the native library directory because that is the one
 * place Android's W^X policy permits; see `tools/aapt2/FINDINGS.md`.
 *
 * Both stages report through `onDiagnostic` while the process runs, rather than
 * only through the [StageResult] they return. aapt2 prints a resource error and
 * then keeps going over the rest of the tree; the user should see the first one
 * then, not after the last.
 */
internal class ResourceStage(private val runner: NativeToolRunner) {

    /**
     * Compiles `res/` into a zip of binary resources.
     *
     * Produces null when the project has no resources at all. That is unusual
     * but legal -- a project of pure Java has nothing to compile -- and is not
     * a failure.
     *
     * Compiles the whole directory in one invocation rather than file by file.
     * That is the wrong shape for incremental builds, where only changed files
     * should be recompiled, but it is one process instead of hundreds, and the
     * input hashing an incremental design needs does not exist yet.
     */
    suspend fun compile(
        layout: ProjectLayout,
        workspace: BuildWorkspace,
        onDiagnostic: (Diagnostic) -> Unit = {},
    ): StageResult<File?> {
        if (!layout.resourceDir.isDirectory) return StageResult.ok(null)

        val output = workspace.compiledProjectResources
        val diagnostics = mutableListOf<Diagnostic>()
        val result = runner.run(
            tool = NativeTool.AAPT2,
            args = listOf(
                "compile",
                "--dir", layout.resourceDir.absolutePath,
                "-o", output.absolutePath,
            ),
            onLine = collector(diagnostics, layout.root, onDiagnostic),
        )

        return interpret(result, diagnostics, "Compiling resources failed.") {
            output.takeIf { it.isFile }
        }
    }

    /**
     * Compiles a dependency's `res/` into its own archive.
     *
     * Separately from the project's, and separately from each other, because
     * aapt2 overlays whole archives in the order they are given at link time.
     * One combined compile would lose that ordering, and with it the rule that
     * the app's own resources win over a library's.
     *
     * Produces null for a library with nothing to compile, which is common:
     * plenty of AARs carry only code.
     */
    suspend fun compileLibrary(
        resourceDir: File,
        index: Int,
        workspace: BuildWorkspace,
        projectRoot: File,
        onDiagnostic: (Diagnostic) -> Unit = {},
    ): StageResult<File?> {
        if (!resourceDir.isDirectory) return StageResult.ok(null)

        val output = workspace.compiledLibraryResources(index)
        val diagnostics = mutableListOf<Diagnostic>()
        val result = runner.run(
            tool = NativeTool.AAPT2,
            args = listOf(
                "compile",
                "--dir", resourceDir.absolutePath,
                "-o", output.absolutePath,
            ),
            onLine = collector(diagnostics, projectRoot, onDiagnostic),
        )

        return interpret(result, diagnostics, "Compiling library resources failed.") {
            output.takeIf { it.isFile }
        }
    }

    /**
     * Links compiled resources and the manifest into an APK containing no code,
     * and generates `R.java` beside it.
     *
     * The generated `R` is what ties the two halves of the build together: it is
     * an input to the Java compiler, so resources have to be linked before any
     * source can be compiled.
     */
    suspend fun link(
        layout: ProjectLayout,
        workspace: BuildWorkspace,
        platform: AndroidPlatform,
        debuggable: Boolean,
        /** Library archives, in overlay order: earliest is weakest. */
        libraryResources: List<File> = emptyList(),
        /** Packages needing their own `R` class; see [DependencyInputs]. */
        libraryPackages: List<String> = emptyList(),
        /** The project's own package, which already gets an `R` class. */
        applicationId: String = "",
        /**
         * The manifest to link, which is the *merged* one when a project has
         * Android libraries. Defaults to the project's own so a caller with no
         * dependencies does not have to think about it.
         */
        manifest: File = layout.manifestFile,
        onDiagnostic: (Diagnostic) -> Unit = {},
    ): StageResult<File?> {
        val compiledResources = workspace.compiledProjectResources

        val args = buildList {
            add("link")
            add("-o"); add(workspace.linkedApk.absolutePath)
            add("-I"); add(platform.androidJar.absolutePath)
            add("--manifest"); add(manifest.absolutePath)
            // Where R.java goes; aapt2 creates the package directories itself.
            add("--java"); add(workspace.generatedJava.absolutePath)
            // Lets a project overlay resources it did not itself declare, which
            // is how AAR resources will merge in once :engine:deps exists.
            add("--auto-add-overlay")
            // Assets go in here rather than in the packaging stage so that they
            // land in the APK under aapt2's own compression rules -- it already
            // knows which extensions must not be deflated.
            if (layout.assetsDir.isDirectory) {
                add("-A"); add(layout.assetsDir.absolutePath)
            }
            if (debuggable) add("--debug-mode")

            // **One `R` class per library package, or the app dies on launch.**
            //
            // aapt2 generates `R` for the manifest's package and nothing else,
            // but a library's compiled code references *its own* -- so
            // `androidx.customview.poolingcontainer.R` is simply not in the
            // APK, and the build succeeds anyway. The first symptom is
            // `NoClassDefFoundError` from a Compose app that installed
            // perfectly. `--extra-packages` is an accumulating flag, so it is
            // repeated rather than joined. FINDINGS section 12.
            //
            // The project's own package is excluded: aapt2 would emit a second,
            // identical `R.java` for it and the Java compiler would reject the
            // duplicate.
            libraryPackages.filter { it.isNotBlank() && it != applicationId }
                .forEach { add("--extra-packages"); add(it) }

            // Order is the overlay rule, and it is the whole reason these are
            // compiled separately: aapt2 lets a later archive override an
            // earlier one, so libraries go first and the project last. Reverse
            // this and a library's `app_name` silently replaces the user's.
            //
            // `-R` is what makes that true, and it is not optional. A positional
            // input is part of the *base* set, where two archives defining one
            // resource name is a hard error -- and two AndroidX libraries at
            // matching versions do exactly that: `compose.ui:ui-android` and
            // `compose.foundation:foundation-android` both ship
            // `string/autofill`. Under `-R` the last one wins, which is the
            // documented behaviour and the one AGP's resource merger provides.
            // The first input has to stay positional: `-R` overlays something,
            // and with every input an overlay there is no base to overlay onto.
            // FINDINGS section 12.
            val inputs = libraryResources.filter { it.isFile } +
                listOfNotNull(compiledResources.takeIf { it.isFile })
            inputs.forEachIndexed { index, archive ->
                if (index > 0) add("-R")
                add(archive.absolutePath)
            }
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val result = runner.run(
            tool = NativeTool.AAPT2,
            args = args,
            onLine = collector(diagnostics, layout.root, onDiagnostic),
        )

        return interpret(result, diagnostics, "Linking resources failed.") {
            workspace.linkedApk.takeIf { it.isFile }
        }
    }

    /**
     * Parses each line as the process writes it, reporting it and keeping it.
     *
     * Both streams are parsed. aapt2 puts every message on stderr and leaves
     * stdout empty, so telling them apart would gain nothing here -- and the
     * older rule this replaces, "use stderr, fall back to stdout", cannot be
     * expressed line by line: whether stderr turns out to be empty is not known
     * until the run is over, by which point the lines have already been handed
     * over.
     *
     * A plain [MutableList] is safe as the sink because [NativeToolRunner]
     * serialises the callback across the two streams it drains.
     */
    private fun collector(
        into: MutableList<Diagnostic>,
        projectRoot: File,
        onDiagnostic: (Diagnostic) -> Unit,
    ): (ToolLine) -> Unit = { line ->
        Aapt2Diagnostics.parseLine(line.text, projectRoot)?.let { diagnostic ->
            into += diagnostic
            onDiagnostic(diagnostic)
        }
    }

    /**
     * Common handling for both invocations.
     *
     * A zero exit code is necessary but not sufficient, on two counts. aapt2
     * exits zero on some errors it has already printed, and it can report
     * success while writing nothing -- which would otherwise surface as a
     * baffling failure three stages later, in a tool that did nothing wrong.
     */
    private inline fun interpret(
        result: AppResult<ToolResult>,
        diagnostics: List<Diagnostic>,
        failureMessage: String,
        produced: () -> File?,
    ): StageResult<File?> = when (result) {
        // The tool never started, so nothing was streamed and there is nothing
        // to report but why.
        is AppResult.Failure -> StageResult.failed(result.error.message)
        is AppResult.Success -> {
            val output = produced()
            when {
                !result.value.isSuccess -> StageResult.failed(failureMessage, diagnostics)
                diagnostics.hasErrors -> StageResult.failed(failureMessage, diagnostics)
                output == null -> StageResult.failed(
                    "$failureMessage aapt2 reported success but produced no output.",
                    diagnostics,
                )
                else -> StageResult.ok(output, diagnostics)
            }
        }
    }
}
