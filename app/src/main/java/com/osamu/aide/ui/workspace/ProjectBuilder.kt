package com.osamu.aide.ui.workspace

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.DebuggerRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import android.os.Build
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.fast.FastBuildSystem
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.engine.fast.ReleaseKeystoreStore
import com.osamu.aide.engine.gradle.GradleToolchainProvider
import com.osamu.aide.engine.fast.KotlinCompiler
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Puts a build engine together for a project and runs it.
 *
 * The engine takes an [com.osamu.aide.engine.fast.AndroidPlatform] rather than
 * finding one, and the platform is a 63 MB download that may not be there yet --
 * so something has to sit between the screen and the engine and join the two.
 * That is all this is. It is deliberately not a ViewModel concern: which engine
 * builds a project is a property of the project, and a second engine (Gradle,
 * in the rootfs) will be chosen here too.
 */
class ProjectBuilder(
    private val toolchain: ToolchainManager,
    private val platforms: AndroidPlatformProvider,
    private val runner: NativeToolRunner,
    private val dependencies: ProjectDependencies,
    /**
     * Asked per build rather than held, so a compiler installed while the app
     * is running is used by the next build. The instance behind it is still
     * reused -- its ~11 s startup is paid per classloader. See
     * [KotlinCompilerSource] for why this is not a nullable Koin singleton.
     */
    private val kotlin: KotlinCompilerSource,
    /**
     * Asked per build, for the same reason [kotlin] is, and with the same
     * consequence for dependency injection: it resolves to null on any device
     * that has not downloaded 551 MB of clang, and Koin cannot hold null in a
     * singleton. The provider is the singleton; the toolchain is not.
     */
    private val native: NativeToolchainProvider,
    /**
     * The Gradle engine, asked per build like the others. It resolves to null
     * on any device without a JDK and a Gradle distribution -- roughly 470 MB
     * that most projects never need, since the fast path is the default.
     */
    private val gradle: GradleToolchainProvider,
    private val dispatchers: DispatcherProvider,
    /**
     * The user's release key, when they have set one up.
     *
     * Null for callers that only build debug -- several tests -- and the
     * engine refuses a release request without it rather than quietly signing
     * with the device's throwaway key.
     */
    private val releaseKeys: ReleaseKeystoreStore? = null,
    /**
     * Cache, not the project directory. Intermediates are large, regenerable,
     * and the system may clear them whenever it likes -- which is exactly the
     * contract a build directory wants.
     */
    private val outputRoot: File,
) {

    /** False means the platform has to be downloaded before anything can build. */
    fun isPlatformInstalled(): Boolean = toolchain.canBuild()

    /**
     * The C/C++ toolchain this project needs and does not have, or null.
     *
     * Null covers both ordinary cases -- the project has no native code, or the
     * toolchain is already installed -- and the one that is out of scope, a
     * device whose ABI has no toolchain built for it. Only a component that can
     * actually be downloaded is returned, so the caller never offers a download
     * that cannot happen.
     */
    fun missingNativeToolchain(project: Project): ToolchainComponent? {
        if (ProjectLayout.of(project).nativeSources().isEmpty()) return null
        if (native.toolchain() != null) return null
        return ToolchainComponent.nativeToolchain(Build.SUPPORTED_ABIS.first())
    }

    /**
     * The next download a Gradle build needs, or null when it has them all.
     *
     * **Nothing offered these before.** The JDK, Gradle and the build tools
     * were defined, pinned and tested as components, and the engine found them
     * where the installer puts them -- but no screen ever installed one, so
     * outside a test harness that staged them by hand a Gradle project could
     * only be refused. One at a time, in the order a build needs them, because
     * each offer is its own dialog and the build resumes after each.
     *
     * The platform is not here: every build needs it, and the caller already
     * offers it with Google's terms.
     */
    fun missingGradleToolchain(project: Project): ToolchainComponent? {
        if (project.engine != BuildEngine.GRADLE) return null
        if (gradle.javaHome() == null) return ToolchainComponent.openJdk(Build.SUPPORTED_ABIS.first())
        if (gradle.gradleHome() == null) return ToolchainComponent.GRADLE
        if (toolchain.canBuild() && gradle.androidSdk() == null) return ToolchainComponent.ANDROID_BUILD_TOOLS
        return null
    }

    /**
     * The Kotlin compiler, when a fast-engine project has Kotlin to compile and
     * the device does not have it yet.
     *
     * **Offered from Build as well as from the editor.** It was only offered
     * when a `.kt` file was opened, so building a Kotlin project from the
     * projects list -- or after the platform download -- ended at "the Kotlin
     * compiler is not installed" with no way to get it.
     */
    fun missingKotlinCompiler(project: Project): ToolchainComponent? {
        if (project.engine != BuildEngine.FAST) return null
        if (project.language != SourceLanguage.KOTLIN && ProjectLayout.of(project).kotlinSources().isEmpty()) return null
        if (kotlin.compiler() != null) return null
        return ToolchainComponent.KOTLIN_COMPILER
    }

    fun build(
        project: Project,
        debuggable: Boolean = true,
        debugger: DebuggerRequest? = null,
    ): Flow<BuildEvent> = flow {
        // **Which engine is a property of the project**, recorded when it was
        // created or imported. Checked before anything else because the Gradle
        // path needs none of what follows: it resolves its own dependencies and
        // finds its own platform, so asking for android.jar or a dependency
        // resolve first would make a Gradle build wait on work it will not use.
        if (project.engine == BuildEngine.GRADLE) {
            emitAll(buildWithGradle(project, debuggable, debugger))
            return@flow
        }

        val androidJar = toolchain.androidJar()
        if (androidJar == null) {
            // Reachable if the platform is deleted between the check and the
            // tap. Reported as an ordinary failed build rather than thrown: the
            // caller is a UI that has a place to show this and none to show a
            // crash.
            emit(
                BuildEvent.Finished(
                    BuildResult.Failure(
                        stage = null,
                        message = "${ToolchainComponent.ANDROID_PLATFORM.displayName} is not installed.",
                        durationMillis = 0,
                    ),
                ),
            )
            return@flow
        }

        // Resolved before the engine starts rather than inside it: a first
        // resolve can take a minute of network, and the build's own stage
        // reporting has no vocabulary for that. It is also cached, so the
        // common case adds a second or two.
        val resolved = dependencies.inputsFor(project) { message ->
            emit(BuildEvent.Note(message))
        }

        val engine = FastBuildSystem(
            runner,
            platforms.platformFor(androidJar),
            dispatchers,
            kotlin.compiler(),
            native.toolchain(),
            releaseKeys,
        )
        emitAll(
            engine.build(
                BuildRequest(
                    project = project,
                    outputDir = outputFor(project),
                    debuggable = debuggable,
                    debugger = debugger,
                    dependencies = resolved,
                ),
            ),
        )
    }

    /**
     * Builds with the project's own Gradle.
     *
     * A missing runtime is reported the way a missing platform is -- an
     * ordinary failed build carrying a sentence -- rather than thrown: the
     * caller is a UI with somewhere to show this and nowhere to show a crash.
     */
    private fun buildWithGradle(
        project: Project,
        debuggable: Boolean,
        debugger: DebuggerRequest?,
    ): Flow<BuildEvent> = flow {
        // **Every build, before the engine is looked for.** The JDK's own
        // `bin/java` and `jspawnhelper` have to point at the launcher this app
        // ships, or Gradle's first fork dies -- and nothing called this: the
        // JDK component could be installed and never run. Idempotent, and a
        // few symlink checks against a build measured in minutes.
        gradle.prepareJdk()
        val engine = gradle.engine()
        if (engine == null) {
            emit(
                BuildEvent.Finished(
                    BuildResult.Failure(
                        stage = null,
                        message = "This project builds with Gradle, which needs a Java runtime " +
                            "and a Gradle distribution. Neither is installed.",
                        durationMillis = 0,
                    ),
                ),
            )
            return@flow
        }
        emitAll(
            engine.build(
                // Both were dropped here: a release build ran assembleDebug and
                // a debug build carried no debugger.
                BuildRequest(
                    project = project,
                    outputDir = outputFor(project),
                    debuggable = debuggable,
                    debugger = debugger,
                ),
            ),
        )
    }

    // Project directory names are unique within the workspace, so this is
    // stable across builds -- which is what lets a rebuild reuse the workspace
    // instead of starting from an empty directory every time.
    private fun outputFor(project: Project): File = File(outputRoot, project.rootDir.name)
}
