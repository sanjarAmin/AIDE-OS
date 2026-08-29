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
import com.osamu.aide.toolchain.nativetools.ClangToolchain
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
    /**
     * The Kotlin compiler, when the device has one.
     *
     * Null is an ordinary state, not a misconfiguration: the compiler is a
     * 54 MB download and a Java project never needs it. A Kotlin project on a
     * device without it is refused by name rather than failing somewhere in the
     * middle of a build.
     */
    private val kotlin: KotlinCompiler? = null,
    /**
     * The C/C++ toolchain, when the device has one.
     *
     * Null the same way [kotlin] is, and more often: clang is a 152 MiB
     * download unpacking to 551 MB, and most projects have no native code at
     * all. A project with C sources on a device without it is refused by name.
     */
    private val clang: ClangToolchain? = null,
) : BuildSystem {

    private val resources = ResourceStage(runner)
    private val javac = JavaCompileStage(dispatchers)
    private val kotlinc = kotlin?.let { KotlinCompileStage(it, dispatchers) }
    private val dexer = DexStage(dispatchers)
    private val nativec = clang?.let { NativeCompileStage(it) }
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

            // Merged before linking, because aapt2 reads the manifest once and
            // whatever it is handed is what the APK declares. A library's
            // components are absent from the built app otherwise, and nothing
            // in the build reports it -- see ManifestMerger.
            val manifest = withContext(dispatchers.io) {
                ManifestMerger.merge(
                    projectManifest = layout.manifestFile,
                    libraries = request.dependencies.libraryManifests,
                    applicationId = request.project.applicationId,
                    output = workspace.mergedManifest,
                )
            }

            reportingStage(BuildStage.LINK_RESOURCES, diagnostics) { onDiagnostic ->
                resources.link(
                    layout = layout,
                    workspace = workspace,
                    platform = platform,
                    debuggable = request.debuggable,
                    libraryResources = libraryResources,
                    libraryPackages = request.dependencies.libraryPackages,
                    applicationId = request.project.applicationId,
                    manifest = manifest,
                    onDiagnostic = onDiagnostic,
                )
            }

            // R.java is an output of linking and an input to compiling, which is
            // why the two halves of the build cannot be reordered or run in
            // parallel however much the wall clock would like them to be.
            val sources = layout.javaSources() + workspace.generatedJavaSources()
            val kotlinSources = layout.kotlinSources()

            // Kotlin first, and given the Java sources too: it reads them for
            // signatures without emitting anything for them, so Kotlin can see
            // Java. ECJ then finds kotlinc's output already in classes/, so
            // Java can see Kotlin. The other order leaves every Kotlin type
            // unresolved in the Java half.
            if (kotlinc != null && kotlinSources.isNotEmpty()) {
                reportingStage(BuildStage.COMPILE_KOTLIN, diagnostics) { onDiagnostic ->
                    kotlinc.compile(
                        kotlinSources = kotlinSources,
                        javaSources = sources,
                        platform = platform,
                        workspace = workspace,
                        projectRoot = layout.root,
                        moduleName = request.project.name,
                        dependencies = request.dependencies.classpath,
                        onDiagnostic = onDiagnostic,
                    )
                }
            }

            stage(BuildStage.COMPILE_JAVA, diagnostics) {
                javac.compile(
                    sources = sources,
                    platform = platform,
                    workspace = workspace,
                    projectRoot = layout.root,
                    // kotlinc's output is already in classes/, and Java needs it
                    // on the classpath to refer to anything Kotlin declared.
                    dependencies = request.dependencies.classpath + workspace.classes,
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

            // After the JVM half rather than before it: the two are
            // independent, and running native code last means a project whose
            // Java does not compile does not first spend a minute on clang.
            //
            // Guarded exactly the way the Kotlin stage is, and for the same
            // reason: an unguarded stage still emits StageStarted, so every
            // Java project would report "Compiling C/C++" and complete it
            // having done nothing. The stage tolerates an empty source list
            // anyway; what this decides is whether the user is told about it.
            val native = if (nativec != null && layout.nativeSources().isNotEmpty()) {
                reportingStage(BuildStage.COMPILE_NATIVE, diagnostics) { onDiagnostic ->
                    nativec.build(
                        layout = layout,
                        workspace = workspace,
                        libraryName = nativeLibraryName(request.project),
                        onDiagnostic = onDiagnostic,
                    )
                }
            } else {
                null
            }

            stage(BuildStage.PACKAGE, diagnostics) {
                packager.pack(
                    workspace = workspace,
                    dexFiles = dexFiles.orEmpty(),
                    nativeLibraries = native?.all.orEmpty(),
                )
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

        // The compiler is a download, and a Kotlin project without it cannot
        // be built. Said here rather than discovered three stages in, where it
        // would surface as every Kotlin type being unresolved in the Java half.
        kotlinc == null &&
            (request.project.language == SourceLanguage.KOTLIN ||
                layout.kotlinSources().isNotEmpty()) ->
            "This project has Kotlin sources; the Kotlin compiler is not installed."

        // Same reasoning as Kotlin's, and the failure it prevents is worse:
        // without the toolchain the build would produce an APK that installs
        // and then dies at System.loadLibrary, on the user's device, with
        // nothing pointing back at a missing download.
        nativec == null && layout.nativeSources().isNotEmpty() ->
            "This project has C/C++ sources; the C/C++ toolchain is not installed."

        layout.javaSources().isEmpty() && layout.kotlinSources().isEmpty() ->
            "This project has no sources."

        else -> platform.validate()
    }

    /**
     * What the built library is called, and therefore what
     * `System.loadLibrary` has to be given.
     *
     * Derived from the project name rather than configured. There is no
     * `CMakeLists.txt` in this model to declare a target in, and inventing a
     * descriptor field for one string would be a worse trade than a convention
     * the user can predict: lower-cased, everything that is not a letter, digit
     * or underscore replaced. A name with nothing usable left falls back to
     * `native`, so the rule always yields something loadable.
     */
    private fun nativeLibraryName(project: com.osamu.aide.core.fs.Project): String =
        project.name.lowercase()
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "native" }

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
