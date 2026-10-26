package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ProjectToolset
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodeSearchToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project() = Project(
        name = "Sample",
        rootDir = temp.root,
        applicationId = "com.example.sample",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    private fun tools(open: Boolean = true) = codeSearchTools(
        project = { if (open) project() else null },
    )

    private fun toolset(open: Boolean = true) =
        ProjectToolset(ProjectFiles(temp.root), tools(open))

    @Test
    fun search_definitions_is_offered_and_is_read_only() {
        val tool = toolset().all().firstOrNull { it.name == "search_definitions" }
        assertTrue("search_definitions must be in toolset", tool != null)
        assertEquals(
            "Symbol search changes nothing, so it must be READ_ONLY",
            ToolRisk.READ_ONLY,
            tool?.risk,
        )
        assertTrue("query is a required parameter", tool?.required?.contains("query") == true)
    }

    @Test
    fun search_definitions_refuses_when_no_project_is_open() = runTest {
        val outcome = toolset(open = false).execute(
            name = "search_definitions",
            input = mapOf("query" to "MainActivity"),
        )
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("No project is open" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun search_definitions_refuses_when_query_is_missing() = runTest {
        val outcome = toolset().execute(
            name = "search_definitions",
            input = emptyMap(),
        )
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("needs a query" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun search_definitions_finds_classes_functions_and_xml_entries() = runTest {
        val srcDir = temp.newFolder("src")
        val javaFile = File(srcDir, "MainActivity.java")
        javaFile.writeText(
            """
            package com.example;
            public class MainActivity extends AppCompatActivity {
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                }
            }
            """.trimIndent(),
        )

        val ktFile = File(srcDir, "Greeter.kt")
        ktFile.writeText(
            """
            package com.example
            class Greeter {
                fun greetUser(name: String): String = "Hello " + name
            }
            """.trimIndent(),
        )

        val manifest = File(temp.root, "AndroidManifest.xml")
        manifest.writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name=".MainActivity" />
                </application>
            </manifest>
            """.trimIndent(),
        )

        // 1. Search for MainActivity (should find java class and XML entry)
        val mainActivityOutcome = toolset().execute(
            name = "search_definitions",
            input = mapOf("query" to "MainActivity"),
        )
        assertTrue(mainActivityOutcome is ProjectFiles.Outcome.Ok)
        val mainText = (mainActivityOutcome as ProjectFiles.Outcome.Ok).content
        assertTrue("Must find Java class", "MainActivity.java" in mainText)
        assertTrue("Must find XML activity reference", "AndroidManifest.xml" in mainText)

        // 2. Search for greetUser (should find Kotlin function)
        val greetOutcome = toolset().execute(
            name = "search_definitions",
            input = mapOf("query" to "greetUser"),
        )
        assertTrue(greetOutcome is ProjectFiles.Outcome.Ok)
        val greetText = (greetOutcome as ProjectFiles.Outcome.Ok).content
        assertTrue("Must find Kotlin function", "Greeter.kt" in greetText)
        assertTrue("fun greetUser" in greetText)

        // 3. Search for a non-existent symbol
        val missingOutcome = toolset().execute(
            name = "search_definitions",
            input = mapOf("query" to "NonExistentClass12345"),
        )
        assertTrue(missingOutcome is ProjectFiles.Outcome.Ok)
        val missingText = (missingOutcome as ProjectFiles.Outcome.Ok).content
        assertTrue("Must report no definitions found", "No definitions found" in missingText)
    }

    /**
     * The path goes through ProjectFiles like every other tool's. Joined on
     * directly, `../` searched outside the project without asking, and the XML
     * pattern quoted whole lines of the app's shared_prefs back to the model.
     */
    @Test
    fun a_path_outside_the_project_is_refused() = runTest {
        val outcome = toolset().execute(
            name = "search_definitions",
            input = mapOf("query" to "url", "path" to "../"),
        )

        assertTrue(outcome.toString(), outcome is ProjectFiles.Outcome.Refused)
    }

    /** A source package called `build` is source; only a build script beside it makes it output. */
    @Test
    fun a_package_called_build_is_searched() = runTest {
        File(temp.newFolder("src", "build"), "BuildService.java")
            .writeText("public class BuildService {}\n")

        val text = (toolset().execute("search_definitions", mapOf("query" to "BuildService")) as ProjectFiles.Outcome.Ok).content

        assertTrue(text, "src/build/BuildService.java" in text)
    }

    /** Both are template languages, and neither was searched at all. */
    @Test
    fun python_and_csharp_are_searched() = runTest {
        File(temp.root, "main.py").writeText("def greet(name):\n    return name\n")
        File(temp.root, "Program.cs").writeText("class Program { static void Main() {} }\n")

        val python = (toolset().execute("search_definitions", mapOf("query" to "greet")) as ProjectFiles.Outcome.Ok).content
        val csharp = (toolset().execute("search_definitions", mapOf("query" to "Program")) as ProjectFiles.Outcome.Ok).content

        assertTrue(python, "main.py" in python)
        assertTrue(csharp, "Program.cs" in csharp)
    }

    /**
     * A link back to an ancestor is not followed. walkTopDown followed it, and
     * the walk only ended when the path grew too long to open.
     */
    @Test(timeout = 10_000)
    fun a_symlink_to_an_ancestor_does_not_loop() = runTest {
        val src = temp.newFolder("src")
        File(src, "A.java").writeText("public class A {}\n")
        java.nio.file.Files.createSymbolicLink(File(src, "loop").toPath(), temp.root.toPath())

        val text = (toolset().execute("search_definitions", mapOf("query" to "A")) as ProjectFiles.Outcome.Ok).content

        assertEquals(text, 1, text.lines().count { "A.java" in it })
    }
}
