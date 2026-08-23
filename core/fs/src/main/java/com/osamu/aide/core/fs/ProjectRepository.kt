package com.osamu.aide.core.fs

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.common.runCatchingResult
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes projects under a single workspace directory.
 *
 * The descriptor is hand-rolled JSON via [JSONObject] rather than a serialization
 * plugin: it is a handful of fields, and keeping codegen out of the build keeps
 * incremental compiles fast across the module graph.
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

    override suspend fun listProjects(): AppResult<List<Project>> =
        withContext(dispatchers.io) {
            runCatchingResult {
                workspaceRoot.mkdirs()
                (workspaceRoot.listFiles() ?: emptyArray())
                    .filter { it.isDirectory && File(it, Project.DESCRIPTOR_NAME).exists() }
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

    private fun readDescriptor(dir: File): Project? = runCatching {
        val json = JSONObject(File(dir, Project.DESCRIPTOR_NAME).readText())
        Project(
            name = json.getString(KEY_NAME),
            rootDir = dir,
            applicationId = json.getString(KEY_APPLICATION_ID),
            language = SourceLanguage.valueOf(json.getString(KEY_LANGUAGE)),
            engine = BuildEngine.valueOf(json.getString(KEY_ENGINE)),
            lastOpenedAt = json.optLong(KEY_LAST_OPENED, 0L),
        )
    }.getOrNull()

    private fun writeDescriptor(project: Project) {
        val json = JSONObject()
            .put(KEY_NAME, project.name)
            .put(KEY_APPLICATION_ID, project.applicationId)
            .put(KEY_LANGUAGE, project.language.name)
            .put(KEY_ENGINE, project.engine.name)
            .put(KEY_LAST_OPENED, project.lastOpenedAt)
        project.descriptorFile.writeText(json.toString(2))
    }

    private fun String.toDirectoryName(): String =
        trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "project" }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_APPLICATION_ID = "applicationId"
        const val KEY_LANGUAGE = "language"
        const val KEY_ENGINE = "engine"
        const val KEY_LAST_OPENED = "lastOpenedAt"
    }
}
