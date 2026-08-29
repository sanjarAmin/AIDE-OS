package com.osamu.aide.core.fs

import java.io.File

/**
 * Where a project keeps its sources.
 *
 * This mirrors the conventional Android/Gradle layout rather than inventing a
 * flatter one. The project model is ours, but the directory shape is the one
 * every Android developer already has in their fingers, and it makes importing
 * an existing project mostly a matter of writing an `aide.json` beside sources
 * that are already in the right place.
 */
class ProjectLayout(val root: File) {

    val mainDir: File get() = File(root, "src/main")
    val manifestFile: File get() = File(mainDir, "AndroidManifest.xml")
    val javaDir: File get() = File(mainDir, "java")
    val resourceDir: File get() = File(mainDir, "res")
    val assetsDir: File get() = File(mainDir, "assets")

    /**
     * C and C++ sources. `src/main/cpp`, which is where the NDK's own Gradle
     * plugin puts them, so an imported project needs nothing moved.
     */
    val nativeDir: File get() = File(mainDir, "cpp")

    /** Every `.java` under [javaDir], in a stable order so builds are repeatable. */
    fun javaSources(): List<File> = javaDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    fun kotlinSources(): List<File> = javaDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /**
     * Every C and C++ source under [nativeDir], in a stable order.
     *
     * Headers are excluded: they are compiled through the sources that include
     * them, and a header compiled on its own is either an error or a wasted
     * object file.
     */
    fun nativeSources(): List<File> = nativeDir
        .walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in NATIVE_EXTENSIONS }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /** True when there is enough here to attempt a build. */
    fun isBuildable(): Boolean = manifestFile.isFile

    companion object {
        private val NATIVE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")

        fun of(project: Project) = ProjectLayout(project.rootDir)
    }
}
