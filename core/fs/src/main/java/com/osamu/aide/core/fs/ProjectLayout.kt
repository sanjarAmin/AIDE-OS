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

    /** A Node project's entry point, whatever `package.json` calls `main`. */
    val nodeEntryPoint: File
        get() {
            val manifest = File(root, "package.json").takeIf { it.isFile }
                ?: return File(root, "index.js")
            // Deliberately a regex and not a JSON parser: this module has no
            // JSON dependency, and the field is written by the template. A
            // hand-edited `package.json` that this cannot read falls back to
            // the conventional name rather than failing to find anything.
            val main = Regex("\"main\"\\s*:\\s*\"([^\"]+)\"")
                .find(manifest.readText())?.groupValues?.get(1)
            return File(root, main ?: "index.js")
        }

    /**
     * Where a run puts npm's home and its cache.
     *
     * Inside the project rather than in app storage, so that deleting a
     * project takes its downloaded packages with it and two projects cannot
     * disagree about a dependency's version. Dot-prefixed because the file
     * tree hides those, and named in [ProjectTemplate]'s `.gitignore` -- the
     * two must stay in step, which is why they are spelled once, here.
     */
    val nodeHome: File get() = File(root, NODE_HOME)

    /** @see nodeHome */
    val nodeCache: File get() = File(root, NODE_CACHE)

    /**
     * True when there is enough here to attempt a build.
     *
     * **An APK build**, which is the only kind this asks about. A Node project
     * has no manifest and is never buildable in this sense; it is *runnable*,
     * which is a different question and deliberately not conflated with it --
     * answering true here would send it into the aapt2 pipeline.
     */
    fun isBuildable(): Boolean = manifestFile.isFile

    companion object {
        const val NODE_HOME = ".aide-home"
        const val NODE_CACHE = ".aide-cache"

        private val NATIVE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")

        fun of(project: Project) = ProjectLayout(project.rootDir)
    }
}
