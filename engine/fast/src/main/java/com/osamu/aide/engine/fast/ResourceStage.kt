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

        val output = File(workspace.compiledResources, "resources.zip")
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
        onDiagnostic: (Diagnostic) -> Unit = {},
    ): StageResult<File?> {
        val compiledResources = File(workspace.compiledResources, "resources.zip")

        val args = buildList {
            add("link")
            add("-o"); add(workspace.linkedApk.absolutePath)
            add("-I"); add(platform.androidJar.absolutePath)
            add("--manifest"); add(layout.manifestFile.absolutePath)
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
            if (compiledResources.isFile) add(compiledResources.absolutePath)
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
