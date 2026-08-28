package com.osamu.aide.core.fs

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.common.runCatchingResult
import kotlinx.coroutines.withContext
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
     * The project root is not always [directory]. A repository is usually a
     * Gradle root holding `app/`, and the module is what the editor and the
     * build engine want -- so the same rule the importer uses applies: the
     * directory itself if it looks like a module, otherwise its single child
     * that does.
     *
     * **Ambiguity is refused rather than guessed at.** A repository with two
     * Android modules has no right answer, and picking one silently leaves the
     * user with a project that is quietly missing half their code.
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
                            "src/main/AndroidManifest.xml, either at the top level or in " +
                            "exactly one subfolder.",
                    ),
                )

            runCatchingResult {
                val project = describe(name, module)
                ProjectDescriptor.write(project)
                project
            }
        }

    /** True when [directory] would adopt without asking anything further. */
    fun isAdoptable(directory: File): Boolean = moduleIn(directory) != null

    private fun moduleIn(root: File): File? {
        if (root.isModule()) return root
        return root.listFiles()
            ?.filter { it.isDirectory && it.isModule() }
            ?.singleOrNull()
    }

    private fun File.isModule(): Boolean = File(this, "src/main/AndroidManifest.xml").isFile

    private fun describe(name: String, rootDir: File): Project {
        val layout = ProjectLayout(rootDir)
        return Project(
            name = name,
            rootDir = rootDir,
            applicationId = packageOf(layout.manifestFile)
                ?: "com.example." + ProjectDescriptor.directoryNameFor(name)
                    .lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" },
            // Any Kotlin in it makes it a Kotlin project, so the engine says so
            // plainly rather than compiling half of it.
            language = if (layout.kotlinSources().isNotEmpty()) {
                SourceLanguage.KOTLIN
            } else {
                SourceLanguage.JAVA
            },
            engine = BuildEngine.FAST,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    /**
     * The manifest's `package`, or null.
     *
     * Null is ordinary rather than an error: AGP 7 moved the application id
     * into the Gradle build and modern manifests carry no package attribute at
     * all. The caller derives one from the name in that case.
     */
    private fun packageOf(manifest: File): String? = runCatching {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
            .documentElement
            .getAttribute("package")
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}
