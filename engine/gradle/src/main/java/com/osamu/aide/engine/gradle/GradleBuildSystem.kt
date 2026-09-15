package com.osamu.aide.engine.gradle

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.BuildSystem
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.toolchain.nativetools.JvmToolchain
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.jar.JarFile

/**
 * The other engine: a project's own Gradle build, run on the device.
 *
 * `:engine:fast` exists because Gradle is slow and needs a JVM; this exists
 * because some projects cannot be built any other way — a custom plugin, an
 * annotation processor, a `buildSrc`. The fast path is the default and this is
 * the escape hatch, which is why it may be absent without the app being broken.
 *
 * **There is no rootfs.** `docs/PLAN.md` planned this as a bridge into a Linux
 * guest under PRoot; spike R11 found that route closed and unnecessary. Gradle
 * runs on Termux's Bionic-built OpenJDK, started by our own launcher, in this
 * app's own process tree. `tools/rootfs/FINDINGS.md`.
 *
 * Gradle is driven through its command-line entry point rather than the Tooling
 * API. The Tooling API's whole job is to start and talk to a daemon in another
 * process, which is the part that needs the most care here; going through
 * `GradleMain` keeps one process to reason about and one stream to read.
 */
class GradleBuildSystem(
    private val jvm: JvmToolchain,
    /** The unpacked distribution, e.g. `<install>/gradle-9.7.1`. */
    private val gradleHome: File,
    private val dispatchers: DispatcherProvider,
    /**
     * Where Gradle keeps its caches. Under the app's files rather than the
     * user's home, which on Android is not a place anything should write to.
     */
    private val gradleUserHome: File,
    /**
     * The SDK to build against, or null on a device where none is installed.
     *
     * Nullable rather than absent because this engine is the escape hatch: the
     * fast engine needs only `android.jar`, so a device can perfectly well have
     * a working IDE and no SDK for Gradle to use. That is a refusal with a
     * sentence, not a broken app.
     */
    private val sdk: AndroidSdk? = null,
) : BuildSystem {

    /** The distribution's entry-point jar; its name carries the version. */
    private val launcherJar: File?
        get() = File(gradleHome, "lib").listFiles()
            ?.firstOrNull { it.name.startsWith("gradle-launcher-") && it.extension == "jar" }

    /**
     * The launcher jar **and every jar its manifest names**.
     *
     * `gradle-launcher-9.7.1.jar` does not contain `GradleMain`. It is a thin
     * jar whose manifest `Class-Path` points at `gradle-gradle-cli-main-*.jar`,
     * which does — and our launcher hands the JVM a flat
     * `-Djava.class.path`, so nothing expands that attribute for us. Putting
     * only the launcher jar on the classpath starts a perfectly healthy JVM
     * that then cannot find the class it was started for, which the launcher
     * reports as exit 6 and reads like a broken install.
     *
     * Read from the manifest rather than hardcoded, because the jar it points
     * at is a detail of the distribution's own layout: Gradle split the
     * launcher at some version, and naming today's second jar here would break
     * on the version that splits it again.
     */
    private fun launcherClassPath(): List<File> {
        val jar = launcherJar ?: return emptyList()
        val lib = jar.parentFile ?: return listOf(jar)
        val referenced = runCatching {
            JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Class-Path") }
        }.getOrNull()
            .orEmpty()
            .split(' ', '\t')
            .filter { it.isNotBlank() }
            .map { File(lib, it.trim()) }
            .filter { it.isFile }
        return listOf(jar) + referenced
    }

    val isInstalled: Boolean get() = jvm.isInstalled && launcherJar != null

    override fun build(request: BuildRequest): Flow<BuildEvent> = channelFlow {
        val startedAt = System.nanoTime()
        val refusal = refuse(request)
        if (refusal != null) {
            send(BuildEvent.Finished(BuildResult.Failure(null, refusal, elapsed(startedAt))))
            return@channelFlow
        }

        val projectRoot = request.project.rootDir
        val diagnostics = mutableListOf<Diagnostic>()
        val transcript = StringBuilder()
        val open = ArrayDeque<Pair<BuildStage, Long>>()
        // **Each stage is reported once.** A dozen Gradle tasks map onto the
        // same kind of work -- mergeDebugResources, processDebugResources and
        // packageDebugResources are all resource linking -- and reporting each
        // one reopens a stage the user already watched finish. The first run of
        // this produced LINK_RESOURCES four times interleaved with DEX twice,
        // which is a flickering progress bar rather than progress.
        val reported = mutableSetOf<BuildStage>()

        // Before Gradle starts, not as part of writing the project: an imported
        // project's local.properties names a desktop SDK path, and AGP prefers
        // it to anything in the environment. AndroidSdk explains why rewriting
        // this particular file is legitimate.
        sdk?.pointProjectAtSdk(projectRoot)

        send(BuildEvent.Note("Running Gradle. The first build downloads its dependencies."))

        val debugInit = request.debugger?.let { GradleDebugAgent.prepare(File(gradleUserHome, "aide-debug"), it) }
        val editorInit = GradleEditorInputs.writeInitScript(File(gradleUserHome, "aide-editor"))

        val result = jvm.run(
            mainClass = GRADLE_MAIN,
            classPath = launcherClassPath(),
            vmOptions = vmOptions(),
            arguments = gradleArguments(request, listOfNotNull(editorInit, debugInit)),
            workingDir = projectRoot,
            environment = mapOf(
                "TMPDIR" to temporaryDir().absolutePath,
                "HOME" to gradleUserHome.absolutePath,
            ),
        ) { line ->
            transcript.appendLine(line.text)

            GradleOutput.diagnosticOf(line.text, projectRoot)?.let {
                diagnostics += it
                trySend(BuildEvent.DiagnosticReported(it))
            }

            GradleOutput.stageOf(line.text)?.takeIf { reported.add(it) }?.let { stage ->
                // Gradle reports a task starting and never that it finished, so
                // each stage is closed when the next one opens and the last by
                // the build itself. Timings are therefore "until the next kind
                // of work began", which is what a progress bar wants anyway.
                closeOpenStage(open, startedAt)
                open.addLast(stage to System.nanoTime())
                trySend(BuildEvent.StageStarted(stage))
            }
        }
        closeOpenStage(open, startedAt)

        send(BuildEvent.Finished(finish(result, request, transcript.toString(), diagnostics, startedAt)))
    }.flowOn(dispatchers.io)

    private fun ProducerScope<BuildEvent>.closeOpenStage(
        open: ArrayDeque<Pair<BuildStage, Long>>,
        startedAt: Long,
    ) {
        val previous = open.removeLastOrNull() ?: return
        trySend(BuildEvent.StageCompleted(previous.first, elapsed(previous.second)))
    }

    /**
     * Records what the editor needs to understand this project, and builds
     * nothing.
     *
     * **The editor used to have to wait for a build.** A Gradle project's
     * classpath is only knowable by running Gradle, so until one had assembled,
     * every `androidx` import in an imported project was unresolved -- and a
     * project that does not yet build, which is the ordinary state of a
     * repository someone has just cloned, never got there at all. This asks for
     * the recording tasks alone: dependencies resolve, `R`, `BuildConfig` and
     * view binding are generated, and nothing is compiled, dexed or signed.
     *
     * It is still a Gradle invocation -- a JVM, a configuration phase and, the
     * first time, a download of AGP -- so it reports progress the way a build
     * does, and when to run one is the app's decision rather than this
     * engine's.
     */
    fun sync(project: Project): Flow<SyncEvent> = channelFlow {
        val startedAt = System.nanoTime()
        val refusal = refuseProject(project)
        if (refusal != null) {
            send(SyncEvent.Finished(false, refusal, elapsed(startedAt)))
            return@channelFlow
        }

        val projectRoot = project.rootDir
        // For the reason build() gives: an imported project's local.properties
        // names a desktop SDK, and AGP prefers it to anything in the environment.
        sdk?.pointProjectAtSdk(projectRoot)
        send(SyncEvent.Note("Asking Gradle what this project is made of."))

        val editorInit = GradleEditorInputs.writeInitScript(File(gradleUserHome, "aide-editor"))
        val transcript = StringBuilder()
        val recorded = mutableSetOf<String>()
        val result = jvm.run(
            mainClass = GRADLE_MAIN,
            classPath = launcherClassPath(),
            vmOptions = vmOptions(),
            arguments = buildList {
                add(GradleEditorInputs.SYNC_TASK)
                addAll(commonArguments(listOf(editorInit)))
            },
            workingDir = projectRoot,
            environment = mapOf(
                "TMPDIR" to temporaryDir().absolutePath,
                "HOME" to gradleUserHome.absolutePath,
            ),
        ) { line ->
            transcript.appendLine(line.text)
            GradleOutput.recordedModuleOf(line.text)?.let {
                if (recorded.add(it)) trySend(SyncEvent.Recorded(it))
            }
        }

        val failure = when (result) {
            is AppResult.Failure -> result.error.message
            is AppResult.Success ->
                if (result.value.isSuccess) {
                    null
                } else {
                    GradleOutput.failureMessage(transcript.toString())
                        ?: "Gradle exited with ${result.value.exitCode}."
                }
        }
        send(
            SyncEvent.Finished(
                succeeded = failure == null,
                message = failure ?: "Read ${recorded.size} module${if (recorded.size == 1) "" else "s"}.",
                durationMillis = elapsed(startedAt),
            ),
        )
    }.flowOn(dispatchers.io)

    /**
     * Why this build cannot start, or null.
     *
     * Checked before anything runs, so a missing toolchain is a sentence rather
     * than a failure deep inside Gradle's own diagnostics.
     */
    private fun refuse(request: BuildRequest): String? = refuseProject(request.project)
        // As in the fast engine: a listening socket and a permission the user
        // did not write, in an app that is not debuggable and so could never
        // be attached to anyway.
        ?: "A release build cannot carry a debugger.".takeIf { !request.debuggable && request.debugger != null }

    /**
     * Why Gradle cannot run here at all, or null.
     *
     * The half of [refuse] that is about the toolchain and the project rather
     * than about what was asked for, so a sync -- which asks for no artifact --
     * is refused with the same sentences.
     */
    private fun refuseProject(project: Project): String? = when {
        !jvm.isInstalled ->
            "The Java runtime is not installed. Gradle builds need it; the fast engine does not."
        launcherJar == null ->
            "Gradle is not installed, or its download did not finish."
        !File(project.rootDir, "settings.gradle.kts").isFile &&
            !File(project.rootDir, "settings.gradle").isFile ->
            "This project has no settings.gradle, so Gradle has nothing to build."
        sdk == null ->
            "No Android SDK is installed. Gradle builds compile against a real SDK; " +
                "the fast engine does not."
        sdk.platformJar == null ->
            "The Android SDK at ${sdk.dir} has no platform installed, so there is " +
                "nothing to compile against."
        // Checked here rather than left to AGP, which does say so itself --
        // after a minute of starting, configuring and executing on hardware
        // that can spare neither.
        !sdk.licenceAccepted ->
            "The Android SDK licence has not been accepted, and Gradle will not " +
                "build until it is."
        else -> null
    }

    private fun gradleArguments(request: BuildRequest, initScripts: List<File>): List<String> = buildList {
        add(if (request.debuggable) "assembleDebug" else "assembleRelease")
        addAll(commonArguments(initScripts))
    }

    /** Everything an invocation of Gradle needs that is not the task to run. */
    private fun commonArguments(initScripts: List<File>): List<String> = buildList {
        // What the editor needs recorded, and the debugger's agent when one was
        // asked for -- each added by an init script rather than by editing the
        // project. See GradleEditorInputs and GradleDebugAgent.
        initScripts.forEach { add("--init-script"); add(it.absolutePath) }
        // **Not a preference.** Gradle's daemon is another JVM, and although
        // the launcher makes one startable, a daemon that outlives the build
        // holds a heap the size of the build on a device that has none to
        // spare. The single-use daemon Gradle forks anyway is enough.
        add("--no-daemon")
        add("-g")
        add(gradleUserHome.absolutePath)
        // **On the command line, not in gradle.properties.** That file is the
        // user's, checked into their repository and carrying their own
        // settings; a build that rewrites it to inject a tool path corrupts
        // something the user owns. A project property is scoped to this
        // invocation and leaves nothing behind.
        sdk?.aapt2Override()?.let { add("-Pandroid.aapt2FromMavenOverride=${it.absolutePath}") }
    }

    private fun vmOptions(): List<String> = listOf(
        // **Gradle ships its own native library, and not for this platform.**
        // `libnative-platform.so` is published for `linux-amd64` and friends,
        // meaning glibc; on Bionic it does not load and Gradle stops before it
        // has configured anything, with "Could not initialize native services".
        // Turning it off is Gradle's own supported answer for a platform it has
        // no build for -- it falls back to pure-Java implementations of the
        // file-system and process handling it would otherwise do natively.
        "-Dorg.gradle.native=false",
        // The Termux JDK bakes in Termux's own prefix as java.io.tmpdir, and
        // that directory does not exist here; Gradle fails inside service
        // construction rather than saying so.
        "-Djava.io.tmpdir=${temporaryDir().absolutePath}",
        "-Duser.home=${gradleUserHome.absolutePath}",
    )

    private fun temporaryDir(): File = File(gradleUserHome, "tmp").apply { mkdirs() }

    private fun finish(
        result: AppResult<com.osamu.aide.toolchain.nativetools.ToolResult>,
        request: BuildRequest,
        transcript: String,
        diagnostics: List<Diagnostic>,
        startedAt: Long,
    ): BuildResult = when (result) {
        is AppResult.Failure ->
            BuildResult.Failure(null, result.error.message, elapsed(startedAt), diagnostics)

        is AppResult.Success ->
            if (!result.value.isSuccess) {
                BuildResult.Failure(
                    stage = null,
                    // Gradle's own sentence when it has one; the exit code
                    // alone tells the user nothing they can act on.
                    message = GradleOutput.failureMessage(transcript)
                        ?: "Gradle exited with ${result.value.exitCode}.",
                    durationMillis = elapsed(startedAt),
                    diagnostics = diagnostics,
                )
            } else {
                val apk = findApk(request)
                if (apk == null) {
                    BuildResult.Failure(
                        BuildStage.PACKAGE,
                        "Gradle reported success but produced no APK.",
                        elapsed(startedAt),
                        diagnostics,
                    )
                } else if (request.debugger != null && !GradleDebugAgent.carriesAgent(apk)) {
                    BuildResult.Failure(
                        BuildStage.PACKAGE,
                        "The app built, but without the debugger in it, so there is nothing to " +
                            "attach to. The debugger needs the app module to use " +
                            "com.android.application with a recent Android Gradle Plugin.",
                        elapsed(startedAt),
                        diagnostics,
                    )
                } else {
                    BuildResult.Success(apk, elapsed(startedAt), diagnostics)
                }
            }
    }

    /**
     * The APK Gradle wrote.
     *
     * Searched for rather than computed: the path depends on the variant, the
     * module layout and any output renaming the project does, none of which
     * this engine gets to decide. The newest one is taken, so a stale artifact
     * from an earlier variant is not mistaken for this build's.
     */
    private fun findApk(request: BuildRequest): File? {
        val variant = if (request.debuggable) "debug" else "release"
        return request.project.rootDir.walkTopDown()
            .filter { it.isFile && it.extension == "apk" && it.path.contains("/outputs/apk/$variant/") }
            .maxByOrNull { it.lastModified() }
    }

    private fun elapsed(from: Long) = (System.nanoTime() - from) / 1_000_000

    private companion object {
        const val GRADLE_MAIN = "org.gradle.launcher.GradleMain"
    }
}
