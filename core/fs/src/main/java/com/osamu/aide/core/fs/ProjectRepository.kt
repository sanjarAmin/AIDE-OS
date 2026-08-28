package com.osamu.aide.core.fs

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.common.runCatchingResult
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads and writes projects under a single workspace directory.
 *
 * The descriptor is hand-rolled JSON -- see [ProjectDescriptor] -- rather than a
 * serialization plugin: it is a handful of fields, and keeping codegen out of
 * the build keeps incremental compiles fast across the module graph.
 */
interface ProjectRepository {
    suspend fun listProjects(): AppResult<List<Project>>

    /**
     * Reads the project rooted at [dir].
     *
     * Separate from [listProjects] because the workspace is reached by path --
     * from navigation, and later from a launcher shortcut or a share intent --
     * and scanning every project to find the one already named is both slower
     * and wrong the moment a project lives outside the workspace root.
     */
    suspend fun openProject(dir: File): AppResult<Project>

    suspend fun createProject(
        name: String,
        applicationId: String,
        language: SourceLanguage,
        engine: BuildEngine,
    ): AppResult<Project>
    suspend fun touch(project: Project): AppResult<Unit>
}

class FileProjectRepository(
    private val workspaceRoot: File,
    private val dispatchers: DispatcherProvider,
) : ProjectRepository {

    /**
     * Every project in the workspace.
     *
     * Looks **one level deeper** as well as at the top, and that is not a
     * convenience. A cloned repository is usually a Gradle root holding `app/`,
     * so the project is the module and the repository is its parent -- which
     * makes the descriptor a grandchild of the workspace root rather than a
     * child. Scanning only the top level meant a repository could clone
     * perfectly and never appear, with no error anywhere to say why.
     *
     * Exactly one level, not a walk. Deeper is someone's `build/` output or a
     * vendored copy of another project, and a projects list that turns those up
     * is worse than one that misses an unusual layout.
     *
     * A directory that has its own descriptor is not descended into: a project
     * containing a project is one project.
     */
    override suspend fun listProjects(): AppResult<List<Project>> =
        withContext(dispatchers.io) {
            runCatchingResult {
                workspaceRoot.mkdirs()
                (workspaceRoot.listFiles() ?: emptyArray())
                    .filter { it.isDirectory }
                    .flatMap { entry ->
                        if (File(entry, Project.DESCRIPTOR_NAME).exists()) {
                            listOf(entry)
                        } else {
                            (entry.listFiles() ?: emptyArray())
                                .filter { it.isDirectory && File(it, Project.DESCRIPTOR_NAME).exists() }
                                .toList()
                        }
                    }
                    .mapNotNull { readDescriptor(it) }
                    .sortedByDescending { it.lastOpenedAt }
            }
        }

    override suspend fun openProject(dir: File): AppResult<Project> =
        withContext(dispatchers.io) {
            runCatchingResult {
                checkNotNull(readDescriptor(dir)) {
                    "${dir.name} has no readable ${Project.DESCRIPTOR_NAME}."
                }
            }
        }

    override suspend fun createProject(
        name: String,
        applicationId: String,
        language: SourceLanguage,
        engine: BuildEngine,
    ): AppResult<Project> = withContext(dispatchers.io) {
        runCatchingResult {
            val dir = File(workspaceRoot, name.toDirectoryName())
            require(!dir.exists()) { "A project named \"$name\" already exists." }
            check(dir.mkdirs()) { "Could not create ${dir.absolutePath}" }

            val project = Project(
                name = name,
                rootDir = dir,
                applicationId = applicationId,
                language = language,
                engine = engine,
                lastOpenedAt = System.currentTimeMillis(),
            )
            writeDescriptor(project)
            // A project with a descriptor and no sources is not something the
            // user can do anything with, and not something the build engine can
            // act on. Creating one means creating something that builds.
            ProjectTemplate.write(project)
            project
        }
    }

    override suspend fun touch(project: Project): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingResult {
                writeDescriptor(project.copy(lastOpenedAt = System.currentTimeMillis()))
            }
        }

    private fun readDescriptor(dir: File): Project? = ProjectDescriptor.read(dir)

    private fun writeDescriptor(project: Project) = ProjectDescriptor.write(project)

    private fun String.toDirectoryName(): String = ProjectDescriptor.directoryNameFor(this)
}
