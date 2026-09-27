package com.osamu.aide.ui.workspace

import android.util.Log
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.DependencyInputs
import com.osamu.aide.engine.deps.Coordinate
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.engine.deps.ResolutionProgress
import com.osamu.aide.engine.deps.ResolvedDependencies
import com.osamu.aide.core.common.AppResult
import java.io.File

/**
 * Resolves a project's declared dependencies, once, for everything that needs
 * them.
 *
 * Both the build engine and the language service want the same classpath, and
 * resolving it twice would mean a minute of network twice over on a cold cache.
 * So the result is held per project and handed to both.
 *
 * Keyed on the project directory *and* the declared coordinates: editing
 * `aide.json` has to invalidate the cache, or adding a dependency would appear
 * to do nothing until the app restarted.
 */
class ProjectDependencies(private val resolver: DependencyResolver) {

    private var cached: Pair<Key, ResolvedDependencies>? = null

    private data class Key(val root: File, val declared: List<String>)

    /**
     * The resolved graph, in the shape a [com.osamu.aide.engine.api.BuildRequest]
     * wants.
     *
     * [onProgress] carries the first resolve's minute of network to whatever is
     * watching; later calls for the same project return without reporting
     * anything, because there is nothing to wait for.
     */
    suspend fun inputsFor(
        project: Project,
        onProgress: suspend (String) -> Unit = {},
    ): DependencyInputs {
        val resolved = resolve(project, onProgress)
        return DependencyInputs(
            classpath = resolved.compileClasspath,
            resourceDirectories = resolved.resourceDirectories,
            // **All four, and each one is load-bearing.** These two were added
            // to the engine with M6 and wired only into its own tests, so a
            // project built through the app got neither: no per-library `R`
            // class, and no library components in the manifest. Both fail after
            // a successful build -- one crashes on launch, the other never
            // reports anything at all -- which is why
            // ProjectDependenciesTest asserts the shape of this object rather
            // than trusting the call site.
            libraryPackages = resolved.libraryPackages,
            libraryManifests = resolved.libraryManifests,
        )
    }

    /** Just the classpath, for `:lsp:java`, which has no use for resources. */
    suspend fun classpathFor(project: Project): List<File> = resolve(project) {}.compileClasspath

    private suspend fun resolve(
        project: Project,
        onProgress: suspend (String) -> Unit,
    ): ResolvedDependencies {
        val key = Key(project.rootDir, project.dependencies)
        cached?.let { (cachedKey, value) -> if (cachedKey == key) return value }

        val coordinates = project.dependencies.mapNotNull { notation ->
            Coordinate.parse(notation).also {
                if (it == null) Log.w(TAG, "ignoring unparseable dependency '$notation'")
            }
        }
        if (coordinates.isEmpty()) {
            return ResolvedDependencies().also { cached = key to it }
        }

        onProgress("Resolving ${coordinates.size} dependencies…")
        val result = resolver.resolve(coordinates) { progress ->
            // Not suspending inside the resolver's callback: it fires from the
            // IO dispatcher mid-resolution, and making it wait on a UI consumer
            // would let a slow collector throttle the download.
            Log.i(TAG, progress.describe())
        }

        val resolved = when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                // A build without them will fail with clearer errors than this
                // one can produce, and the editor should not refuse to open a
                // project because a repository was unreachable.
                onProgress("Could not resolve dependencies: ${result.error.message}")
                ResolvedDependencies()
            }
        }

        if (resolved.unresolved.isNotEmpty()) {
            onProgress("Unresolved: ${resolved.unresolved.joinToString()}")
        } else if (resolved.dependencies.isNotEmpty()) {
            onProgress("Resolved ${resolved.dependencies.size} artifacts")
        }

        cached = key to resolved
        return resolved
    }

    private fun ResolutionProgress.describe(): String = when (this) {
        is ResolutionProgress.Collecting -> "collecting $root"
        is ResolutionProgress.Downloading -> "downloading $artifact ($index/$total)"
        is ResolutionProgress.Extracting -> "extracting $artifact"
    }

    private companion object {
        const val TAG = "ProjectDependencies"
    }
}
