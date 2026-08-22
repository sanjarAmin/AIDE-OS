package com.osamu.aide.engine.fast

import android.os.Build
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.BuildSystem
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The bundled build engine: aapt2, ECJ, D8 and apksig, all on the device.
 *
 * Everything but aapt2 runs in this process, which is what makes the whole path
 * possible without a Linux userland -- see docs/PLAN.md. The stages themselves
 * hold the interesting decisions; this only sequences them, reports what they
 * say, and stops at the first one that fails.
 */
class FastBuildSystem(
    runner: NativeToolRunner,
    private val platform: AndroidPlatform,
    private val dispatchers: DispatcherProvider,
) : BuildSystem {

    private val resources = ResourceStage(runner)
    private val javac = JavaCompileStage(dispatchers)
    private val dexer = DexStage(dispatchers)
    private val packager = PackageStage(dispatchers)
    private val signer = SigningStage(dispatchers)

    override fun build(request: BuildRequest): Flow<BuildEvent> = flow {
        val startedAt = System.nanoTime()
        val diagnostics = mutableListOf<Diagnostic>()
        val layout = ProjectLayout.of(request.project)

        refuse(request, layout)?.let { reason ->
            // Refused before any stage started, so there is no stage to name.
            emit(BuildEvent.Finished(BuildResult.Failure(null, reason, elapsed(startedAt))))
            return@flow
        }

        val workspace = BuildWorkspace(request.outputDir)
        withContext(dispatchers.io) { workspace.prepare() }
        val minSdk = ProjectManifest.minSdk(layout.manifestFile)

        try {
            stage(BuildStage.COMPILE_RESOURCES, diagnostics) {
                resources.compile(layout, workspace)
            }

            stage(BuildStage.LINK_RESOURCES, diagnostics) {
                resources.link(layout, workspace, platform, request.debuggable)
            }

            // R.java is an output of linking and an input to compiling, which is
            // why the two halves of the build cannot be reordered or run in
            // parallel however much the wall clock would like them to be.
            val sources = layout.javaSources() + workspace.generatedJavaSources()
            stage(BuildStage.COMPILE_JAVA, diagnostics) {
                javac.compile(sources, platform, workspace, layout.root)
            }

            val dexFiles = stage(BuildStage.DEX, diagnostics) {
                dexer.dex(
                    classesDir = workspace.classes,
                    platform = platform,
                    workspace = workspace,
                    minSdk = minSdk,
                    debuggable = request.debuggable,
                    projectRoot = layout.root,
                )
            }

            stage(BuildStage.PACKAGE, diagnostics) {
                packager.pack(workspace, dexFiles.orEmpty())
            }

            stage(BuildStage.SIGN, diagnostics) {
                val key = withContext(dispatchers.io) { DebugSigningKey.load() }
                signer.sign(workspace.unsignedApk, workspace.outputApk, key, minSdk)
            }

            emit(
                BuildEvent.Finished(
                    BuildResult.Success(workspace.outputApk, elapsed(startedAt), diagnostics),
                ),
            )
        } catch (failure: StageFailed) {
            emit(
                BuildEvent.Finished(
                    BuildResult.Failure(
                        failure.stage,
                        failure.message,
                        elapsed(startedAt),
                        diagnostics,
                    ),
                ),
            )
        }
    }

    /**
     * Why this project cannot be built at all, or null if it can.
     *
     * Checked up front rather than left to fail inside a stage. A missing
     * android.jar surfaces from ECJ as hundreds of "cannot be resolved" errors
     * against the user's own code, and an unsupported device surfaces from aapt2
     * as a linker message about libbase -- both of which read as the user's
     * fault, and neither of which is.
     */
    private fun refuse(request: BuildRequest, layout: ProjectLayout): String? = when {
        !layout.isBuildable() ->
            "This project has no AndroidManifest.xml."

        // The runtime gate the API 30 floor needs: minSdk is 26 because the
        // editor works below 30, so a device this old must be told it cannot
        // build rather than discover it when aapt2 fails to exec.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
            "Building needs Android 11 or newer. This device is running " +
                "Android ${Build.VERSION.RELEASE}; the editor still works."

        // Kotlin is a compiler and a plugin away, both proved by spike R2 and
        // neither wired in. Saying so beats producing an APK silently missing
        // every Kotlin class in it.
        request.project.language == SourceLanguage.KOTLIN ||
            layout.kotlinSources().isNotEmpty() ->
            "The fast build engine does not compile Kotlin yet."

        layout.javaSources().isEmpty() ->
            "This project has no Java sources."

        else -> platform.validate()
    }

    /**
     * Runs one stage, reporting it, and aborts the build if it fails.
     *
     * Diagnostics are emitted as the stage produces them *and* accumulated, so a
     * caller watching the flow sees each problem when it is found while the
     * final result still carries the whole set.
     */
    private suspend fun <T> FlowCollector<BuildEvent>.stage(
        stage: BuildStage,
        into: MutableList<Diagnostic>,
        run: suspend () -> StageResult<T>,
    ): T? {
        emit(BuildEvent.StageStarted(stage))
        val startedAt = System.nanoTime()

        val result = run()
        into += result.diagnostics
        result.diagnostics.forEach { emit(BuildEvent.DiagnosticReported(it)) }

        if (!result.succeeded) {
            throw StageFailed(stage, result.failure ?: "${stage.displayName} failed.")
        }

        emit(BuildEvent.StageCompleted(stage, elapsed(startedAt)))
        return result.value
    }

    /** Unwinds to the end of the build. Never leaves this class. */
    private class StageFailed(
        val stage: BuildStage,
        override val message: String,
    ) : Exception(message)

    private fun elapsed(sinceNanos: Long): Long =
        (System.nanoTime() - sinceNanos) / 1_000_000
}
