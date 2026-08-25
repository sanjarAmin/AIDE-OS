package com.osamu.aide.engine.fast

import java.io.File

/**
 * Where a build puts everything it makes.
 *
 * All of it is intermediate and all of it is disposable, which is why the whole
 * tree lives under a caller-supplied directory rather than in the project: the
 * user's project directory should hold only what the user wrote, and the system
 * is free to delete a cache at any time.
 */
class BuildWorkspace(val root: File) {

    val compiledResources: File get() = File(root, "res")
    val generatedJava: File get() = File(root, "generated/java")
    val classes: File get() = File(root, "classes")
    val dex: File get() = File(root, "dex")

    /** The project's own resources, compiled to aapt2's binary format. */
    val compiledProjectResources: File get() = File(compiledResources, "resources.zip")

    /**
     * One archive per dependency that carries resources.
     *
     * Indexed rather than named after the artifact: the index *is* the overlay
     * order, and encoding it in the filename keeps that order visible in a
     * workspace someone is debugging by hand.
     */
    fun compiledLibraryResources(index: Int): File =
        File(compiledResources, "library-%03d.zip".format(index))

    /** aapt2's output: resources and a binary manifest, but no code yet. */
    val linkedApk: File get() = File(root, "linked.apk")
    val unsignedApk: File get() = File(root, "unsigned.apk")
    val outputApk: File get() = File(root, "app-debug.apk")

    /** The `R.java` aapt2 generated. An input to the Java compiler, not a source. */
    fun generatedJavaSources(): List<File> = generatedJava
        .walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /**
     * Clears and recreates the tree.
     *
     * Deliberately not incremental. Reusing a partially-written workspace from
     * a build that failed or was cancelled is how you get an APK containing
     * last run's classes, and that is a far worse failure than a slow build.
     * Incrementality belongs at the stage level, keyed on input hashes.
     */
    fun prepare() {
        root.deleteRecursively()
        listOf(compiledResources, generatedJava, classes, dex).forEach { it.mkdirs() }
    }
}
