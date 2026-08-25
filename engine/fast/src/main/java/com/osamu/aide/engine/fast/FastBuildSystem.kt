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
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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

    // channelFlow, not flow: the resource stages report diagnostics from the
    // coroutines draining aapt2's pipes, and a plain flow may only be emitted to
    // from the coroutine collecting it. The stage helpers below funnel those
    // back onto this coroutine, which channelFlow permits and flow does not.
    override fun build(request: BuildRequest): Flow<BuildEvent> = channelFlow {
        val startedAt = System.nanoTime()
        val diagnostics = mutableListOf<Diagnostic>()
        val layout = ProjectLayout.of(request.project)

        refuse(request, layout)?.let { reason ->
            // Refused before any stage started, so there is no stage to name.
            send(BuildEvent.Finished(BuildResult.Failure(null, reason, elapsed(startedAt))))
            return@channelFlow
        }

        val workspace = BuildWorkspace(request.outputDir)
        withContext(dispatchers.io) { workspace.prepare() }
        val minSdk = ProjectManifest.minSdk(layout.manifestFile)

        try {
            reportingStage(BuildStage.COMPILE_RESOURCES, diagnostics) { onDiagnostic ->
                resources.compile(layout, workspace, onDiagnostic)
            }

            // Each dependency's resources compile into their own archive, in a
            // fixed order, so linking can overlay them beneath the project's.
            // Failing the build over one of them is deliberate: a library whose
            // resources did not compile produces an R class missing symbols the
            // user's code refers to, and that surfaces as errors in *their*
            // file.
            val libraryResources = mutableListOf<File>()
            request.dependencies.resourceDirectories.forEachIndexed { index, directory ->
                reportingStage(BuildStage.COMPILE_RESOURCES, diagnostics) { onDiagnostic ->
                    resources.compileLibrary(directory, index, workspace, layout.root, onDiagnostic)
                }?.let(libraryResources::add)
            }

            reportingStage(BuildStage.LINK_RESOURCES, diagnostics) { onDiagnostic ->
                resources.link(
                    layout = layout,
                    workspace = workspace,
                    platform = platform,
                    debuggable = request.debuggable,
                    libraryResources = libraryResources,
                    onDiagnostic = onDiagnostic,
                )
            }

            // R.java is an output of linking and an input to compiling, which is
            // why the two halves of the build cannot be reordered or run in
            // parallel however much the wall clock would like them to be.
            val sources = layout.javaSources() + workspace.generatedJavaSources()
            stage(BuildStage.COMPILE_JAVA, diagnostics) {
                javac.compile(
                    sources = sources,
                    platform = platform,
                    workspace = workspace,
                    projectRoot = layout.root,
                    dependencies = request.dependencies.classpath,
                )
            }

            val dexFiles = stage(BuildStage.DEX, diagnostics) {
                dexer.dex(
                    classesDir = workspace.classes,
                    platform = platform,
                    workspace = workspace,
                    minSdk = minSdk,
                    debuggable = request.debuggable,
                    projectRoot = layout.root,
                    dependencies = request.dependencies.classpath,
                )
            }

            stage(BuildStage.PACKAGE, diagnostics) {
                packager.pack(workspace, dexFiles.orEmpty())
            }

            stage(BuildStage.SIGN, diagnostics) {
                val key = withContext(dispatchers.io) { DebugSigningKey.load() }
                signer.sign(workspace.unsignedApk, workspace.outputApk, key, minSdk)
            }

            send(
                BuildEvent.Finished(
                    BuildResult.Success(workspace.outputApk, elapsed(startedAt), diagnostics),
                ),
            )
        } catch (failure: StageFailed) {
            send(
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
     * Diagnostics are emitted when the stage returns *and* accumulated, so a
     * caller watching the flow sees each problem while the final result still
     * carries the whole set. For a stage that can report as it works, prefer
     * [reportingStage].
     */
    private suspend fun <T> ProducerScope<BuildEvent>.stage(
        stage: BuildStage,
        into: MutableList<Diagnostic>,
        run: suspend () -> StageResult<T>,
    ): T? {
        send(BuildEvent.StageStarted(stage))
        val startedAt = System.nanoTime()

        val result = run()
        into += result.diagnostics
        result.diagnostics.forEach { send(BuildEvent.DiagnosticReported(it)) }

        return finish(stage, startedAt, result)
    }

    /**
     * Runs a stage that reports diagnostics while it works.
     *
     * The point of the exercise: aapt2 prints an error and carries on over the
     * rest of the resource tree, and on a large project that is seconds during
     * which the user could already be reading the first failure. A stage that
     * only reported through its return value made that impossible however
     * promptly the tool spoke.
     *
     * The callback fires on whichever coroutine is draining the tool's pipes, so
     * diagnostics cross back over a channel and are sent from here. That keeps
     * every BuildEvent ordered and coming from one coroutine, and it is why the
     * channel is unbounded: a send that blocked would be blocking a pipe reader,
     * which is how the deadlock [com.osamu.aide.toolchain.nativetools.NativeToolRunner]
     * documents comes back.
     *
     * The [StageResult]'s own diagnostics are ignored on purpose -- they are the
     * same objects the callback already delivered, and re-reporting them would
     * show the user every problem twice.
     */
    private suspend fun <T> ProducerScope<BuildEvent>.reportingStage(
        stage: BuildStage,
        into: MutableList<Diagnostic>,
        run: suspend (onDiagnostic: (Diagnostic) -> Unit) -> StageResult<T>,
    ): T? {
        send(BuildEvent.StageStarted(stage))
        val startedAt = System.nanoTime()

        val live = Channel<Diagnostic>(Channel.UNLIMITED)
        val result = coroutineScope {
            val work = async {
                try {
                    run { diagnostic -> live.trySend(diagnostic) }
                } finally {
                    live.close()
                }
            }
            for (diagnostic in live) {
                into += diagnostic
                send(BuildEvent.DiagnosticReported(diagnostic))
            }
            work.await()
        }

        return finish(stage, startedAt, result)
    }

    /** Closes a stage out: fail the build, or report how long it took. */
    private suspend fun <T> ProducerScope<BuildEvent>.finish(
        stage: BuildStage,
        startedAt: Long,
        result: StageResult<T>,
    ): T? {
        if (!result.succeeded) {
            throw StageFailed(stage, result.failure ?: "${stage.displayName} failed.")
        }

        send(BuildEvent.StageCompleted(stage, elapsed(startedAt)))
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
