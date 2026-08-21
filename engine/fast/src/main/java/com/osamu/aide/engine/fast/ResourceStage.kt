package com.osamu.aide.engine.fast

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.hasErrors
import com.osamu.aide.toolchain.nativetools.NativeTool
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.ToolResult
import java.io.File

/**
 * The two aapt2 stages: compile resources, then link them into an APK.
 *
 * This is the only part of the pipeline that leaves the JVM. aapt2 is a real
 * process, executed out of the native library directory because that is the one
 * place Android's W^X policy permits; see `tools/aapt2/FINDINGS.md`.
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
    ): StageResult<File?> {
        if (!layout.resourceDir.isDirectory) return StageResult.ok(null)

        val output = File(workspace.compiledResources, "resources.zip")
        val result = runner.run(
            NativeTool.AAPT2,
            listOf(
                "compile",
                "--dir", layout.resourceDir.absolutePath,
                "-o", output.absolutePath,
            ),
        )

        return interpret(result, layout.root, "Compiling resources failed.") {
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
            if (debuggable) add("--debug-mode")
            if (compiledResources.isFile) add(compiledResources.absolutePath)
        }

        val result = runner.run(NativeTool.AAPT2, args)

        return interpret(result, layout.root, "Linking resources failed.") {
            workspace.linkedApk.takeIf { it.isFile }
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
        projectRoot: File,
        failureMessage: String,
        produced: () -> File?,
    ): StageResult<File?> = when (result) {
        is AppResult.Failure -> StageResult.failed(result.error.message)
        is AppResult.Success -> {
            val diagnostics = Aapt2Diagnostics.parse(result.value.diagnostics, projectRoot)
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
