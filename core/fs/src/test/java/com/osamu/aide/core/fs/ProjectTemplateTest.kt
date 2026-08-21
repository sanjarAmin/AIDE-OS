package com.osamu.aide.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectTemplateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun project(
        name: String = "Demo",
        applicationId: String = "com.example.demo",
        language: SourceLanguage = SourceLanguage.JAVA,
    ) = Project(
        name = name,
        rootDir = temporaryFolder.newFolder(name.replace(" ", "-")),
        applicationId = applicationId,
        language = language,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    @Test
    fun `writes a buildable project`() {
        val project = project()

        ProjectTemplate.write(project)

        val layout = ProjectLayout.of(project)
        assertTrue("no manifest", layout.manifestFile.isFile)
        assertTrue("not considered buildable", layout.isBuildable())
        assertEquals(
            listOf("MainActivity.java"),
            layout.javaSources().map { it.name },
        )
    }

    @Test
    fun `sources land in a directory matching the package`() {
        val project = project(applicationId = "com.example.demo")

        ProjectTemplate.write(project)

        val expected = File(ProjectLayout.of(project).javaDir, "com/example/demo/MainActivity.java")
        assertTrue("expected source at ${expected.path}", expected.isFile)
    }

    @Test
    fun `the generated activity reads a string resource`() {
        // This is the point of the template: the reference only compiles if
        // aapt2 linked resources and generated R.java for the Java compiler.
        // Without it, half a broken pipeline would still produce an APK.
        val project = project()

        ProjectTemplate.write(project)

        val source = ProjectLayout.of(project).javaSources().single().readText()
        assertTrue("activity does not reference R", source.contains("R.string.greeting"))

        val strings = File(ProjectLayout.of(project).resourceDir, "values/strings.xml").readText()
        assertTrue("greeting string not declared", strings.contains("name=\"greeting\""))
    }

    @Test
    fun `the manifest declares the application id as its package`() {
        // aapt2 link reads the package from the manifest; without it the R class
        // is generated under the wrong name and nothing resolves.
        val project = project(applicationId = "com.example.other")

        ProjectTemplate.write(project)

        val manifest = ProjectLayout.of(project).manifestFile.readText()
        assertTrue(manifest.contains("package=\"com.example.other\""))
    }

    @Test
    fun `a kotlin project gets a kotlin activity instead`() {
        val project = project(language = SourceLanguage.KOTLIN)

        ProjectTemplate.write(project)

        val layout = ProjectLayout.of(project)
        assertTrue("java sources should be empty", layout.javaSources().isEmpty())
        assertEquals(listOf("MainActivity.kt"), layout.kotlinSources().map { it.name })
    }

    @Test
    fun `a project name with XML syntax does not corrupt strings`() {
        val project = project(name = "Fish & Chips <v2>")

        ProjectTemplate.write(project)

        val strings = File(ProjectLayout.of(project).resourceDir, "values/strings.xml").readText()
        assertTrue("ampersand not escaped: $strings", strings.contains("Fish &amp; Chips"))
        assertTrue("angle brackets not escaped: $strings", strings.contains("&lt;v2&gt;"))
    }

    @Test
    fun `an empty project is not buildable`() {
        val project = project()

        assertTrue(!ProjectLayout.of(project).isBuildable())
    }
}
