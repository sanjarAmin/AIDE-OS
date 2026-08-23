package com.osamu.aide.core.fs

import org.json.JSONObject
import java.io.File

/**
 * Reads and writes `aide.json`.
 *
 * Extracted from [FileProjectRepository] because importing writes one too, and
 * two copies of the field names is exactly the kind of duplication that ends
 * with a project the importer creates and the repository cannot read.
 */
object ProjectDescriptor {

    fun read(dir: File): Project? = runCatching {
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

    fun write(project: Project) {
        val json = JSONObject()
            .put(KEY_NAME, project.name)
            .put(KEY_APPLICATION_ID, project.applicationId)
            .put(KEY_LANGUAGE, project.language.name)
            .put(KEY_ENGINE, project.engine.name)
            .put(KEY_LAST_OPENED, project.lastOpenedAt)
        project.descriptorFile.writeText(json.toString(2))
    }

    /** A project name, reduced to something safe to use as a directory name. */
    fun directoryNameFor(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "project" }

    private const val KEY_NAME = "name"
    private const val KEY_APPLICATION_ID = "applicationId"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ENGINE = "engine"
    private const val KEY_LAST_OPENED = "lastOpenedAt"
}
