package com.osamu.aide.core.fs

import java.io.File

/**
 * The starting contents of a new project, and the reason for writing them.
 *
 * **A template is a claim about what the device can do.** Every one here builds
 * or runs on a device today with the toolchain that language already ships --
 * that is the bar for being in [ALL], and it is why there is no Flutter entry
 * (`tools/flutter/FINDINGS.md`). A picker offering a starter nothing on the
 * phone can execute teaches the user that the ▶ button is unreliable, which is
 * a much more expensive lesson than a shorter list.
 *
 * Each carries an [objective] rather than a description, and the difference is
 * the point: with one starter per language the name said everything, but with
 * several the user is choosing between things that all "work", so what they
 * need to read is *what this one is for*. The text is written to finish the
 * sentence "pick this one to ...".
 *
 * Subclasses live beside this file rather than in a `template` package because
 * `sealed` admits subclasses only from the same package, and the exhaustive
 * list is worth more than the tidier directory: adding a template without
 * adding it to [ALL] is then a compile-time omission the catalog test catches
 * rather than a starter nobody can select.
 *
 * @param dependencies Maven coordinates the generated sources need, which the
 *   repository copies onto the project. Only [ComposeApp] has any: every other
 *   template is deliberately resolvable-free so that creating a project works
 *   with no network, which is the state a phone is in more often than a laptop.
 */
sealed class ProjectTemplate(
    val id: String,
    val displayName: String,
    val objective: String,
    val language: SourceLanguage,
    val dependencies: List<String> = emptyList(),
) {

    /** Writes this template's files under [project]'s root. */
    abstract fun write(project: Project)

    companion object {

        /**
         * Every template the picker offers, in the order it offers them.
         *
         * **Held by [Catalog] and not by this field, and that is load-bearing.**
         * A `val` here would be a static field of `ProjectTemplate`, so it is
         * built by that class's initialiser -- which runs *before* any
         * subclass's. Touching a template object first therefore initialised
         * the superclass, which built this list out of objects that did not
         * exist yet, and left **nulls** in it:
         *
         * ```
         * NullPointerException: Attempt to invoke virtual method
         *   ProjectTemplate.getLanguage() on a null object reference
         * ```
         *
         * It depends entirely on which class something touches first, so it is
         * invisible until it is not: the same two tests passed in one order and
         * crashed in the other when JUnit happened to reorder them, and a
         * screen reading `ALL` first would never have shown it.
         * `ProjectTemplateCatalogTest` asserts the list has no holes.
         */
        val ALL: List<ProjectTemplate> get() = Catalog.ALL

        fun byId(id: String?): ProjectTemplate? = ALL.firstOrNull { it.id == id }

        fun forLanguage(language: SourceLanguage): List<ProjectTemplate> =
            ALL.filter { it.language == language }

        /**
         * What a caller gets when it names a language and not a template.
         *
         * Every language has one, including the two the picker does not offer
         * on their own -- [SourceLanguage.C] and [SourceLanguage.CPP] reach
         * this through an imported project, and answering with the first
         * template of that language is better than the alternative, which was
         * `else ->` handing a JNI project a bare Java Activity.
         */
        fun defaultFor(language: SourceLanguage): ProjectTemplate =
            forLanguage(language).firstOrNull() ?: BasicJavaApp

        /**
         * The default template for [project]'s language.
         *
         * Kept because importing and adopting a project both want "make this
         * buildable" without having an opinion about which starter, and
         * because every call site that predates the catalog means exactly
         * this.
         */
        fun write(project: Project) = defaultFor(project.language).write(project)
    }
}

/**
 * The catalog, kept outside [ProjectTemplate] so it cannot initialise too early.
 *
 * This object is not a `ProjectTemplate`, so nothing initialises it as a side
 * effect of constructing one. Its list is built the first time somebody asks
 * for it, by which point every template object exists. See
 * [ProjectTemplate.Companion.ALL] for the crash that made this necessary.
 *
 * **Grouped by language, and the grouping is the order.** The picker heads each
 * run of one language, so a list that interleaves them -- which this did, as
 * Java, Kotlin, Java, Kotlin -- prints "JAVA" twice and "KOTLIN" twice and
 * reads as a rendering fault. Every test passed through it: they assert one row
 * per template and that each objective is reachable, and neither looks at a
 * heading. Opening the dialog is what showed it.
 *
 * Within a language, ordered by how far the user is from a running program
 * rather than alphabetically: the starter that needs nothing downloaded comes
 * first, and the one that needs a resolve or an install comes last.
 */
private object Catalog {
    val ALL: List<ProjectTemplate> = listOf(
        BasicJavaApp,
        TwoScreenJavaApp,
        BasicKotlinApp,
        ComposeApp,
        CppNativeLibraryApp,
        CNativeLibraryApp,
        NodeScript,
        NodeHttpServer,
        NodeCommandLineTool,
        NodeDependencyDemo,
        PythonScript,
        PythonCommandLineTool,
        PythonDependencyDemo,
        MonoConsoleApp,
        MonoCollectionsApp,
    )
}

/**
 * The files every Android template needs, and nothing more.
 *
 * Shared rather than repeated because the manifest is the file a template is
 * most likely to get subtly wrong -- a missing `android:exported` is a install
 * failure on API 31+, and it would be wrong in six places instead of one.
 */
internal object AndroidScaffold {

    /**
     * One entry in the manifest's `<application>`.
     *
     * [launcher] is false for every activity but the first: two launcher
     * entries put two icons in the phone's app drawer, which looks like a
     * packaging bug and is one.
     */
    data class ActivityEntry(val className: String, val launcher: Boolean = false)

    /**
     * Creates `src/main`, the manifest and `strings.xml`, and returns the
     * directory the sources go in.
     *
     * The string resource is not decoration. The generated app reads one from
     * code, and that single line is what makes a template a real test of the
     * build engine rather than a placeholder: it only compiles if aapt2 linked
     * the resources, generated `R.java`, and the compiler was handed it. A
     * template with no resource reference would still build with half the
     * pipeline broken.
     */
    fun scaffold(
        project: Project,
        activities: List<ActivityEntry>,
        strings: Map<String, String>,
    ): File {
        val layout = ProjectLayout.of(project)
        val packageDir = File(layout.javaDir, project.applicationId.replace('.', '/'))
        packageDir.mkdirs()
        layout.resourceDir.resolve("values").mkdirs()

        layout.manifestFile.writeText(manifest(project.applicationId, activities))
        File(layout.resourceDir, "values/strings.xml")
            .writeText(strings(mapOf("app_name" to project.name) + strings))
        return packageDir
    }

    private fun manifest(applicationId: String, activities: List<ActivityEntry>): String {
        val entries = activities.joinToString("\n\n") { activity ->
            val filter = if (!activity.launcher) {
                ""
            } else {
                """
                |
                |            <intent-filter>
                |                <action android:name="android.intent.action.MAIN" />
                |                <category android:name="android.intent.category.LAUNCHER" />
                |            </intent-filter>
                """.trimMargin()
            }
            """
            |        <activity
            |            android:name=".${activity.className}"
            |            android:exported="${activity.launcher}">$filter
            |        </activity>
            """.trimMargin()
        }

        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            |    package="$applicationId"
            |    android:versionCode="1"
            |    android:versionName="1.0">
            |
            |    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
            |
            |    <application
            |        android:label="@string/app_name"
            |        android:theme="@android:style/Theme.Material.Light">
            |$entries
            |    </application>
            |</manifest>
        """.trimMargin() + "\n"
    }

    private fun strings(values: Map<String, String>): String {
        val entries = values.entries.joinToString("\n") { (name, value) ->
            "    <string name=\"$name\">${value.xmlEscaped()}</string>"
        }
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<resources>
            |$entries
            |</resources>
        """.trimMargin() + "\n"
    }

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * What a run leaves behind, and must not be committed.
 *
 * Shared by the languages that run rather than build, because they all put
 * their working directories inside the project -- see [ProjectLayout.runHome].
 * The file tree hides them; the Git panel would not.
 */
internal fun writeRunIgnores(project: Project, extra: List<String> = emptyList()) {
    File(project.rootDir, ".gitignore").writeText(
        (
            extra + listOf(
                "${ProjectLayout.RUN_HOME}/",
                "${ProjectLayout.RUN_CACHE}/",
                "${ProjectLayout.BUILD}/",
            )
            ).joinToString(separator = "\n", postfix = "\n"),
    )
}
