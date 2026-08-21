package com.osamu.aide.core.fs

import java.io.File

/**
 * Languages a project's sources are compiled from. Drives which toolchain the
 * build engine selects; see the language rollout in the project plan.
 */
enum class SourceLanguage(val displayName: String) {
    JAVA("Java"),
    KOTLIN("Kotlin"),
    C("C"),
    CPP("C++"),
    JAVASCRIPT("JavaScript"),
}

/**
 * Which engine builds this project.
 *
 * [FAST] is the bundled ECJ/kotlinc -> D8 -> aapt2 -> apksig pipeline: it runs
 * entirely on ART plus one native aapt2 binary, so it needs no Linux userland.
 * [GRADLE] shells out to a real Gradle/AGP inside the optional rootfs, for
 * projects the fast path cannot model.
 */
enum class BuildEngine(val displayName: String) {
    FAST("Fast"),
    GRADLE("Gradle"),
}

/**
 * An AIDE-OS project. Described by an `aide.json` descriptor at [rootDir], which
 * is our own project model rather than a parsed Gradle build -- Gradle projects
 * are imported into this shape on a best-effort basis.
 */
data class Project(
    val name: String,
    val rootDir: File,
    val applicationId: String,
    val language: SourceLanguage,
    val engine: BuildEngine,
    val lastOpenedAt: Long,
) {
    val descriptorFile: File get() = File(rootDir, DESCRIPTOR_NAME)

    companion object {
        const val DESCRIPTOR_NAME = "aide.json"
    }
}
