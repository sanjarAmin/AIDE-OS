package com.osamu.aide.core.fs

import java.io.File

/** One file a template would write, relative to the project root. */
data class TemplateFile(val path: String, val bytes: Long)

/**
 * What [ProjectTemplate.write] would put on disk, without putting it there.
 *
 * **Produced by running the real thing into a scratch directory**, not by a
 * second list each template also declares. That is the whole design: a declared
 * list is a description that drifts, and a preview which quietly disagrees with
 * what Create produces is worse than no preview -- it is the same failure as a
 * project file that lies about how the project is built, which is why
 * [MonoConsoleApp] has no `.csproj`.
 *
 * It costs a few small writes and a delete. Measured at well under the frame
 * budget for every template in the catalog, but it is still disk I/O and must
 * not run on the main thread; the dialog computes it on the IO dispatcher.
 *
 * [name] and the application id derived from it are the ones the user is
 * typing, so the package directory shown is the package directory they will
 * get -- see [ProjectDescriptor.applicationIdFor]. A blank name previews under
 * a placeholder rather than showing nothing, because the file *shape* is what
 * the user is reading and it does not depend on the name.
 *
 * @param scratch a directory the caller owns and may have cleared; a cache
 *   directory is right. This creates a child of it and deletes it again.
 */
fun ProjectTemplate.preview(
    name: String,
    scratch: File,
    engine: BuildEngine = BuildEngine.FAST,
): List<TemplateFile> {
    val effectiveName = name.trim().ifEmpty { "Demo" }
    // Named for the template so two previews cannot race into one directory,
    // and cleared first because a previous run's files would read as this
    // template's.
    val root = File(scratch, "preview-$id").apply {
        deleteRecursively()
        mkdirs()
    }

    return try {
        val project = Project(
            name = effectiveName,
            rootDir = root,
            applicationId = ProjectDescriptor.applicationIdFor(effectiveName),
            language = language,
            engine = engine,
            lastOpenedAt = 0L,
            dependencies = dependencies,
        )
        write(project)

        root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                TemplateFile(
                    // Forward slashes, and relative: this is shown to a person
                    // beside a file tree that uses them, and the scratch path
                    // is an implementation detail they must never see.
                    path = file.relativeTo(root).invariantSeparatorsPath,
                    bytes = file.length(),
                )
            }
            // Directories before their neighbours, then by name, which is the
            // order the file tree will show them in.
            .sortedBy { it.path }
            .toList()
    } finally {
        // Always, including when a template throws: leaving a half-written
        // preview behind would make the *next* preview of it wrong, and the
        // symptom would be a file list that grows as you click around.
        root.deleteRecursively()
    }
}
