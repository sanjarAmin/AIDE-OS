package com.osamu.aide.core.fs

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a directory that is about to become a project is: which folder is the
 * project, its application id, its language, and which engine builds it.
 *
 * One set of rules for [ProjectImporter] and [ProjectAdoption], because two
 * answers is how a repository imports as one project and clones as another.
 * They were two copies of the same code until the rule changed.
 *
 * **A Gradle root is taken whole, and built by Gradle.** The rule used to be
 * "the folder with `src/main/AndroidManifest.xml`, or its only child that has
 * one", which suited the fast engine and nothing else: picking a cloned Android
 * Studio project imported `app/` alone, dropped every library module beside it,
 * ignored the dependencies its `build.gradle` declared, and marked it for an
 * engine that could not build it -- and a library module with a manifest of its
 * own made the whole thing refuse as ambiguous. That was tolerable while the
 * Gradle engine could not be installed from the app. It can now, so a project
 * with `settings.gradle` is what Gradle says it is. Found measuring a
 * twenty-module project on 2026-09-14.
 */
object ProjectDescription {

    private val SETTINGS = listOf("settings.gradle.kts", "settings.gradle")
    private val BUILD_FILES = setOf("build.gradle.kts", "build.gradle")

    /** Not project content, and not worth walking. Matches the importer's skip list. */
    private val SKIPPED = setOf(".git", "build", ".gradle", ".idea", "node_modules")

    fun isGradleRoot(directory: File): Boolean = SETTINGS.any { File(directory, it).isFile }

    /** Whether a folder, known only by the names of its children, is a Gradle root. */
    fun isGradleRoot(childNames: Collection<String>): Boolean = SETTINGS.any { it in childNames }

    fun isModule(directory: File): Boolean = ProjectLayout(directory).manifestFile.isFile

    fun describe(name: String, rootDir: File): Project {
        val gradle = isGradleRoot(rootDir)
        return Project(
            name = name,
            rootDir = rootDir,
            applicationId = (if (gradle) gradleApplicationId(rootDir) else null)
                ?: packageOf(ProjectLayout(rootDir).manifestFile)
                ?: "com.example." + ProjectDescriptor.directoryNameFor(name)
                    .lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" },
            // Any Kotlin in it makes it a Kotlin project, so the engine says so
            // plainly rather than compiling half of it. In a Gradle root the
            // Kotlin can be in any module.
            language = if (hasKotlin(rootDir, gradle)) SourceLanguage.KOTLIN else SourceLanguage.JAVA,
            engine = if (gradle) BuildEngine.GRADLE else BuildEngine.FAST,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    private fun hasKotlin(rootDir: File, gradle: Boolean): Boolean =
        if (!gradle) {
            ProjectLayout(rootDir).kotlinSources().isNotEmpty()
        } else {
            projectFiles(rootDir).any { it.extension == "kt" }
        }

    /**
     * The `applicationId` an application module's build file declares, or its
     * `namespace` when it declares none.
     *
     * Read as text, not evaluated: a build script is a program, and this only
     * wants a good first answer for a descriptor the user can correct. It is not
     * load-bearing for debugging, which takes the package the installer reports.
     */
    internal fun gradleApplicationId(rootDir: File): String? {
        val scripts = projectFiles(rootDir, maxDepth = 3).filter { it.name in BUILD_FILES }.toList()
        val texts = scripts.map { it.readText() }
        texts.firstNotNullOfOrNull { APPLICATION_ID.find(it)?.groupValues?.get(1) }?.let { return it }
        return texts.filter { "com.android.application" in it }
            .firstNotNullOfOrNull { NAMESPACE.find(it)?.groupValues?.get(1) }
    }

    private fun projectFiles(rootDir: File, maxDepth: Int = Int.MAX_VALUE): Sequence<File> =
        rootDir.walkTopDown()
            .maxDepth(maxDepth)
            .onEnter { it == rootDir || (it.name !in SKIPPED && !it.name.startsWith(".")) }
            .filter { it.isFile }

    /**
     * The manifest's `package`, or null.
     *
     * Null is ordinary rather than an error: AGP 7 moved the application id
     * into the Gradle build and modern manifests carry no package attribute at
     * all.
     */
    private fun packageOf(manifest: File): String? = runCatching {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
            .documentElement
            .getAttribute("package")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    // `applicationId = "a.b"` (Kotlin DSL) and `applicationId "a.b"` (Groovy).
    private val APPLICATION_ID = Regex("""\bapplicationId\s*=?\s*["']([\w.]+)["']""")
    private val NAMESPACE = Regex("""\bnamespace\s*=?\s*["']([\w.]+)["']""")
}
