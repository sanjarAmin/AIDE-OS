package com.osamu.aide.core.fs

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.common.runCatchingResult
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a directory that is already on disk into a project.
 *
 * [ProjectImporter] does this for a folder picked through the Storage Access
 * Framework, where the work is dominated by reading a SAF tree and copying it.
 * A cloned repository is already here, in the right place, on a real
 * filesystem -- all that is missing is the `aide.json` that makes the projects
 * list see it.
 *
 * The **rules are the importer's**, and deliberately so: which directory counts
 * as the module, and how an application id and language are derived from it.
 * Two answers to "is this a module" is how a repository imports as one project
 * and clones as another.
 */
class ProjectAdoption(private val dispatchers: DispatcherProvider) {

    /**
     * Writes a descriptor for [directory] and returns the project.
     *
     * The project root is not always [directory], and the rule is the
     * importer's: a Gradle root is the project, whole; otherwise the directory
     * if it is a module, or its single child that is. See [ProjectDescription].
     *
     * **Ambiguity is refused rather than guessed at.** Two Android modules with
     * no Gradle build to relate them have no right answer, and picking one
     * silently leaves the user with a project quietly missing half their code.
     */
    suspend fun adopt(directory: File, name: String): AppResult<Project> =
        withContext(dispatchers.io) {
            if (!directory.isDirectory) {
                return@withContext AppResult.Failure(
                    AppError("${directory.name} is not a directory."),
                )
            }
            val module = moduleIn(directory)
                ?: return@withContext AppResult.Failure(
                    AppError(
                        "No Android module found in ${directory.name}. It needs a " +
                            "settings.gradle, or a src/main/AndroidManifest.xml either at the " +
                            "top level or in exactly one subfolder.",
                    ),
                )

            runCatchingResult {
                val project = ProjectDescription.describe(name, module)
                ProjectDescriptor.write(project)
                project
            }
        }

    /** True when [directory] would adopt without asking anything further. */
    fun isAdoptable(directory: File): Boolean = moduleIn(directory) != null

    private fun moduleIn(root: File): File? {
        // A Gradle root is the project, all of it. See ProjectDescription.
        if (ProjectDescription.isGradleRoot(root)) return root
        if (root.isModule()) return root
        return root.listFiles()
            ?.filter { it.isDirectory && it.isModule() }
            ?.singleOrNull()
    }

    private fun File.isModule(): Boolean = ProjectDescription.isModule(this)
}
