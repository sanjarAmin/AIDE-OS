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

    /**
     * Creates a project and writes [template]'s files into it.
     *
     * [language] is still a parameter rather than being read off the template,
     * because it is what the *project* is -- the descriptor, the run dispatch
     * and the editor all ask the project and never the template, which is gone
     * the moment creation finishes. Passing both means a caller can create a
     * Java project from a template that writes Kotlin, which is wrong; the
     * default argument makes the honest case the easy one.
     */
    suspend fun createProject(
        name: String,
        applicationId: String,
        language: SourceLanguage,
        engine: BuildEngine,
        template: ProjectTemplate = ProjectTemplate.defaultFor(language),
    ): AppResult<Project>
    suspend fun touch(project: Project): AppResult<Unit>

    /**
     * Changes which engine builds [project], and returns it as it now is.
     *
     * The engine was chosen once, at creation, and could never be changed
     * again -- so a project made with the fast path and later needing AGP had
     * to be recreated. It is one field in the descriptor and always was.
     */
    suspend fun setEngine(project: Project, engine: BuildEngine): AppResult<Project>
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
        template: ProjectTemplate,
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
                // The template's, not the caller's: a Compose starter is not
                // buildable without them, and asking the screen to know which
                // coordinates each template needs would put the catalog in two
                // places. Every other template declares none.
                dependencies = template.dependencies,
            )
            writeDescriptor(project)
            // A project with a descriptor and no sources is not something the
            // user can do anything with, and not something the build engine can
            // act on. Creating one means creating something that builds.
            template.write(project)
            project
        }
    }

    override suspend fun setEngine(
        project: Project,
        engine: BuildEngine,
    ): AppResult<Project> = withContext(dispatchers.io) {
        runCatchingResult {
            // Written before it is returned: the caller puts this in its state,
            // and a state that disagrees with the descriptor survives until the
            // next open and then silently reverts.
            project.copy(engine = engine).also { writeDescriptor(it) }
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
