package com.osamu.aide.engine.mono

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.core.fs.ProjectTemplate
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.RunEvent
import com.osamu.aide.engine.api.RunRequest
import com.osamu.aide.engine.api.RunResult
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import com.osamu.aide.toolchain.nativetools.MonoToolchain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **Every C# template compiles and runs with the mcs this app ships.**
 *
 * Written because one of them did not, and nothing caught it.
 * `MonoConsoleApp`'s first draft used a `record` and a switch expression -- both
 * perfectly ordinary modern C#, both rejected by `lib/mono/4.5/mcs.exe` with
 * `CS1525` -- so the template created a project that could never run, and the
 * user would have met the failure as an error in source they did not write.
 *
 * `ProjectTemplateCatalogTest` could not catch it: it checks what a template
 * *writes*. Only the compiler has an opinion about whether the C# is C# this
 * compiler knows.
 *
 * **What mcs 6.14 accepts is not "C# 7", and the boundary is not intuitive.**
 * Measured on the device, one feature at a time:
 *
 * | Accepted | Rejected |
 * |---|---|
 * | named tuples, `out var` | `when` in a `switch` |
 * | interpolation with alignment and format specifiers | local functions |
 * | expression-bodied members, `nameof` | `record`, switch expressions |
 *
 * Tuples and `out var` shipped in C# 7.0, and so did `when` clauses and local
 * functions -- so "this is a C# 7 compiler" is exactly the shorthand that
 * produces a template which does not build. `tools/mono/FINDINGS.md` §6.
 *
 * Driven by the catalog, so a new C# template is covered without editing this.
 */
@RunWith(AndroidJUnit4::class)
class MonoTemplateBuildTest {

    private lateinit var context: Context
    private lateinit var engine: MonoRunSystem

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "mono")
        install(root)
        assumeTrue(
            "no mono staged: tools/mono/fetch-mono.sh, then push mono.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(root, "bin/mono-sgen").isFile,
        )
        val launch = LinkerLaunch.forThisProcess()
        assumeTrue("no dynamic linker for this ABI", launch.isAvailable)

        engine = MonoRunSystem(
            mono = MonoToolchain(root, launch),
            dispatchers = DefaultDispatcherProvider(),
            workspace = File(context.filesDir, "mono-template-workspace").apply { mkdirs() },
        )
    }

    private fun install(root: File) {
        if (File(root, "bin/mono-sgen").isFile) return
        val archive = File(context.getExternalFilesDir(null), "mono.tar")
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    @Test
    fun every_csharp_template_compiles_and_runs() = runBlocking {
        val templates = ProjectTemplate.ALL.filter { it.language == SourceLanguage.CSHARP }
        assumeTrue("no C# templates to check", templates.isNotEmpty())

        val failures = mutableListOf<String>()

        for (template in templates) {
            // Its own directory: MonoRunSystem compiles *every* `.cs` under the
            // project root, so two templates sharing one would compile to a
            // program with two `Main` methods and fail for a reason that has
            // nothing to do with either.
            val root = File(context.filesDir, "cs-template-${template.id}").apply {
                deleteRecursively()
                mkdirs()
            }
            val project = Project(
                name = "Demo ${template.id}",
                rootDir = root,
                applicationId = "com.example.${template.id.replace("-", "")}",
                language = template.language,
                engine = BuildEngine.FAST,
                lastOpenedAt = 0L,
            )
            template.write(project)

            val events = engine
                .run(
                    RunRequest(
                        projectDir = root,
                        entryPoint = File(root, "Program.cs"),
                    ),
                )
                .toList()
            val output = events.filterIsInstance<RunEvent.Output>().map { it.line }
            val result = (events.last() as RunEvent.Finished).result
            Log.i(TAG, "${template.id}: $result")

            when {
                // A compile error ends the run as `Failed`, not `Exited(1)`:
                // the program never ran, so it cannot have returned anything.
                // That is the distinction this test is really reading.
                result is RunResult.Failed -> failures +=
                    "${template.id} did not compile: ${result.message}\n  " +
                        output.joinToString("\n  ")
                result is RunResult.Exited && !result.succeeded -> failures +=
                    "${template.id} exited ${result.exitCode}:\n  " + output.joinToString("\n  ")
                // A program that compiled, ran and printed nothing is a
                // template with nothing to show, which is its own defect.
                output.none { it.isNotBlank() } -> failures +=
                    "${template.id} ran and printed nothing"
                else -> Log.i(TAG, "${template.id}: ${output.size} lines, first: ${output.first()}")
            }
        }

        assertTrue("C# templates that do not work:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        const val TAG = "MonoTemplateBuildTest"
    }
}
