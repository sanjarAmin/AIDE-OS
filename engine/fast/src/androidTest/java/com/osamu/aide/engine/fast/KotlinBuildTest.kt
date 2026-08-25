package com.osamu.aide.engine.fast

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildStage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes

/**
 * M4's other half: a project with Kotlin in it builds to an installable APK.
 *
 * The compiler is a 54 MB dex archive staged from assets, the same way
 * `android.jar` is -- it is not in git, and `tools/kotlinc/FINDINGS.md` says
 * where it comes from. Skipped rather than failed when it is absent, because a
 * checkout without it is the normal state and a red test would say the wrong
 * thing about the code.
 *
 * Slow on purpose and unavoidably: spike R2 measured ~11 s for a one-file
 * compile, nearly all of it compiler startup, and this pays that once.
 */
@RunWith(AndroidJUnit4::class)
class KotlinBuildTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var kotlin: KotlinCompiler

    @Before
    fun setUp() {
        fixture = EngineTestFixture("kotlin-build-test")
        fixture.assumeAapt2Supported()

        val archive = stage("kotlinc.jar")
        val stdlib = stage("kotlin-stdlib.jar")
        assumeTrue("kotlinc.jar is not staged; see tools/kotlinc/FINDINGS.md", archive != null)
        assumeTrue("kotlin-stdlib.jar is not staged", stdlib != null)

        kotlin = KotlinCompiler(
            KotlinToolchain(archive!!, stdlib!!),
            File(fixture.context.cacheDir, "kotlin-host"),
        )
    }

    /** Null when the asset is absent, so the test can skip rather than fail. */
    private fun stage(name: String): File? {
        val target = File(fixture.workDir, name)
        if (target.isFile) return target
        return runCatching {
            fixture.context.assets.open(name).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            // Since API 29 the platform refuses to load a dex the app can still
            // write to -- the same W^X reasoning that governs executing
            // binaries. Without this, PathClassLoader throws SecurityException.
            target.setReadOnly()
            target
        }.getOrNull()
    }

    private fun engine(withKotlin: Boolean = true) = FastBuildSystem(
        fixture.runner,
        fixture.platform,
        DefaultDispatcherProvider(),
        if (withKotlin) kotlin else null,
    )

    @Test
    fun a_kotlin_source_file_builds_into_a_signed_apk() = runTest(timeout = 5.minutes) {
        val project = fixture.project(applicationId = "com.example.demo")
        val layout = ProjectLayout.of(project)
        File(layout.javaDir, "com/example/demo/Greeting.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example.demo

                object Greeting {
                    fun text(): String = "hello from Kotlin"
                }
                """.trimIndent(),
            )
        }

        val workspace = File(fixture.workDir, "build-kotlin")
        val events = engine().build(BuildRequest(project, workspace)).toList()
        val result = (events.last() as BuildEvent.Finished).result

        assertTrue(
            "build failed: ${(result as? BuildResult.Failure)?.message} " +
                "${result.diagnostics.map { it.describe() }}",
            result is BuildResult.Success,
        )

        assertTrue(
            "the Kotlin stage never ran",
            events.filterIsInstance<BuildEvent.StageStarted>()
                .any { it.stage == BuildStage.COMPILE_KOTLIN },
        )

        // The point of the whole exercise: the Kotlin class is really in the
        // dex. A build that compiled it and dropped it before packaging would
        // still be a green build and a broken app.
        val apk = (result as BuildResult.Success).apk
        val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("no dex in the APK, entries were $entries", entries.any { it.endsWith(".dex") })
        Log.i(TAG, "built ${apk.name}, ${apk.length()} bytes")
    }

    /** Java sees Kotlin, which is what the stage ordering exists for. */
    @Test
    fun java_can_call_into_kotlin() = runTest(timeout = 5.minutes) {
        val project = fixture.project(applicationId = "com.example.demo")
        val layout = ProjectLayout.of(project)

        File(layout.javaDir, "com/example/demo/Greeting.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example.demo

                object Greeting {
                    @JvmStatic
                    fun text(): String = "hello"
                }
                """.trimIndent(),
            )
        }
        // Appended to the template activity so the call is compiled for real.
        File(layout.javaDir, "com/example/demo/MainActivity.java").let { activity ->
            activity.writeText(
                activity.readText().replace(
                    "setContentView(text);",
                    "setContentView(text);\n        text.setText(Greeting.text());",
                ),
            )
        }

        val workspace = File(fixture.workDir, "build-mixed")
        val events = engine().build(BuildRequest(project, workspace)).toList()
        val result = (events.last() as BuildEvent.Finished).result

        assertTrue(
            "a Java file calling Kotlin did not build: " +
                "${(result as? BuildResult.Failure)?.message} " +
                "${result.diagnostics.map { it.describe() }}",
            result is BuildResult.Success,
        )
    }

    /** Without the compiler, a Kotlin project is refused by name. */
    @Test
    fun a_kotlin_project_without_the_compiler_is_refused_clearly() = runTest {
        val project = fixture.project()
        File(ProjectLayout.of(project).javaDir, "com/example/demo/Thing.kt").apply {
            parentFile?.mkdirs()
            writeText("package com.example.demo\nclass Thing")
        }

        val events = engine(withKotlin = false)
            .build(BuildRequest(project, File(fixture.workDir, "build-none")))
            .toList()
        val result = (events.last() as BuildEvent.Finished).result

        assertTrue(result is BuildResult.Failure)
        assertEquals(
            "This project has Kotlin sources; the Kotlin compiler is not installed.",
            (result as BuildResult.Failure).message,
        )
    }

    private companion object {
        const val TAG = "KotlinBuild"
    }
}
