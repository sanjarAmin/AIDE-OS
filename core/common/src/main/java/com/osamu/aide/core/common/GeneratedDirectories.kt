package com.osamu.aide.core.common

import java.io.File

/**
 * Which directories in a project are regenerable output rather than source.
 *
 * One rule for everything that walks a project -- the importer, the file tree,
 * the project description and the assistant's file tools -- because the four
 * copies of it that existed had the same defect.
 *
 * **`build` is a build tool's output, and also an ordinary package name.**
 * Each copy skipped every directory called `build` at any depth. So a source
 * package `com/example/build/` -- this repository has one, holding
 * `BuildService` -- was left behind by the importer, hidden from the file tree,
 * and invisible to the assistant, which then said such code did not exist.
 *
 * What separates the two is where they sit. Gradle, npm and CMake put their
 * `build` beside the script that produced it; a package never has one of those
 * next to it. Deciding needs only the directory's siblings, which even a
 * Storage Access Framework cursor can supply.
 */
object GeneratedDirectories {

    /** Never source, wherever they are. */
    private val ALWAYS = setOf(".git", ".gradle", ".idea", "node_modules")

    /** A `build` beside one of these is that tool's output. */
    private val BUILD_SCRIPTS = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "package.json", "CMakeLists.txt",
    )

    /**
     * Whether a directory called [name] is output, given its [siblings]' names.
     * Siblings are only asked for when the name is `build`.
     */
    fun isGenerated(name: String, siblings: () -> Collection<String>): Boolean = when (name) {
        in ALWAYS -> true
        "build" -> siblings().any { it in BUILD_SCRIPTS }
        else -> false
    }

    /** The same, for a directory on disk. */
    fun isGenerated(directory: File): Boolean =
        isGenerated(directory.name) { directory.parentFile?.list()?.toList().orEmpty() }
}
