package com.osamu.aide.ui.workspace

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import com.osamu.aide.engine.fast.FastBuildSystem
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
    private val dispatchers: DispatcherProvider,
    /**
     * Cache, not the project directory. Intermediates are large, regenerable,
     * and the system may clear them whenever it likes -- which is exactly the
     * contract a build directory wants.
     */
    private val outputRoot: File,
) {

    /** False means the platform has to be downloaded before anything can build. */
    fun isPlatformInstalled(): Boolean = toolchain.canBuild()

    fun build(project: Project): Flow<BuildEvent> = flow {
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

        val engine = FastBuildSystem(runner, platforms.platformFor(androidJar), dispatchers)
        emitAll(
            engine.build(
                BuildRequest(
                    project = project,
                    outputDir = outputFor(project),
                    dependencies = resolved,
                ),
            ),
        )
    }

    // Project directory names are unique within the workspace, so this is
    // stable across builds -- which is what lets a rebuild reuse the workspace
    // instead of starting from an empty directory every time.
    private fun outputFor(project: Project): File = File(outputRoot, project.rootDir.name)
}
