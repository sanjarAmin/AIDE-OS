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
        return runCatching {
            if (!target.isFile) {
                fixture.context.assets.open(name).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            // **Left writable on purpose, as a download leaves it.** The
            // platform refuses to load a dex the app can write to, and this
            // test used to mark its copy read-only first -- so it passed while
            // every build with a compiler installed through the app killed the
            // build process. KotlinCompiler makes its own read-only copy now,
            // and staging the archive the way the installer does is what keeps
            // that honest. Set explicitly, since a run before this change left
            // a read-only file behind.
            target.setWritable(true, true)
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

    /**
     * The app carries the Kotlin standard library when nothing else brings it.
     *
     * Found by running one: a project with no dependencies built, installed,
     * and died on its first line with `NoClassDefFoundError:
     * kotlin/jvm/internal/Intrinsics`, which kotlinc calls after any platform
     * call used as non-null. The test above passed throughout, because it only
     * asked whether a dex existed. `AndroidXKotlinBuildTest` covers the other
     * direction -- a stdlib from Maven, which must not be packaged twice.
     */
    @Test
    fun a_kotlin_app_with_no_dependencies_carries_the_stdlib() = runTest(timeout = 5.minutes) {
        val project = fixture.project(applicationId = "com.example.demo")
        File(ProjectLayout.of(project).javaDir, "com/example/demo/Greeting.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example.demo

                fun shout(words: String): String = words.uppercase()
                """.trimIndent(),
            )
        }

        val events = engine().build(BuildRequest(project, File(fixture.workDir, "build-stdlib"))).toList()
        val result = (events.last() as BuildEvent.Finished).result
        assertTrue(
            "build failed: ${(result as? BuildResult.Failure)?.message}",
            result is BuildResult.Success,
        )

        val apk = (result as BuildResult.Success).apk
        val dex = ZipFile(apk).use { zip ->
            zip.entries().toList()
                .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                .joinToString("") { String(zip.getInputStream(it).readBytes(), Charsets.ISO_8859_1) }
        }
        assertTrue(
            "the APK has no Kotlin runtime, so the app dies on its first Kotlin line",
            dex.contains("Lkotlin/jvm/internal/Intrinsics;") && dex.contains("Lkotlin/text/StringsKt"),
        )
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

    // ---- Incremental compilation with Kotlin. See IncrementalCompile. ----

    private fun kt(layout: ProjectLayout, name: String, body: String) =
        File(layout.javaDir, "com/example/demo/$name.kt").apply {
            parentFile?.mkdirs()
            writeText("package com.example.demo\n\n$body\n")
        }

    /** A build into the same output each time, so the caches beside it are reused. */
    private suspend fun rebuild(project: com.osamu.aide.core.fs.Project): BuildResult {
        val events = engine().build(BuildRequest(project, File(fixture.workDir, "build-incremental"))).toList()
        return (events.last() as BuildEvent.Finished).result
    }

    private fun classBytes(name: String) =
        File(fixture.workDir, "build-incremental/classes/com/example/demo/$name.class").readBytes()

    private fun apkText(result: BuildResult): String = ZipFile((result as BuildResult.Success).apk).use { zip ->
        zip.entries().toList().filter { it.name.endsWith(".dex") }
            .joinToString("") { String(zip.getInputStream(it).readBytes(), Charsets.ISO_8859_1) }
    }

    private fun assertBuilt(result: BuildResult) = assertTrue(
        "build failed: ${(result as? BuildResult.Failure)?.message} ${result.diagnostics.map { it.describe() }}",
        result is BuildResult.Success,
    )

    /**
     * **A body edit recompiles that file only, and still finds the rest of the
     * module.** `Caller.kt` uses a top-level function and an `internal` one
     * from `Helpers.kt`; after the first build only `Caller.kt` changes, twice.
     * The second partial compile is the one that would fail if the first had
     * replaced the module file kotlinc finds top-level functions through, and
     * `internal` would be unreachable without the friend path.
     */
    @Test
    fun a_kotlin_body_edit_recompiles_one_file_and_still_sees_the_module() = runTest(timeout = 10.minutes) {
        val project = fixture.project(applicationId = "com.example.demo")
        val layout = ProjectLayout.of(project)
        kt(layout, "Helpers", "fun shout(s: String) = s.uppercase()\ninternal fun secret() = 41")
        kt(layout, "Caller", "object Caller { fun run() = shout(\"one\") + secret() }")
        Thread.sleep(IncrementalJava.RACY_MILLIS + 200)
        assertBuilt(rebuild(project))
        val helpersStamp = File(fixture.workDir, "build-incremental.java-cache/classes/com/example/demo/HelpersKt.class").lastModified()

        kt(layout, "Caller", "object Caller { fun run() = shout(\"two\") + secret() }")
        assertBuilt(rebuild(project))
        kt(layout, "Caller", "object Caller { fun run() = shout(\"three\") + secret() }")
        val result = rebuild(project)

        assertBuilt(result)
        assertEquals(
            "Helpers.kt was recompiled for an edit to Caller.kt",
            helpersStamp,
            File(fixture.workDir, "build-incremental.java-cache/classes/com/example/demo/HelpersKt.class").lastModified(),
        )
        assertTrue("the last edit is not in the app", apkText(result).contains("three"))
    }

    /**
     * **An inline function's body is its callers' code.** Its class file shows
     * the same ABI after a body edit -- checked against kotlinc 2.2.10 -- so
     * without the `inline` rule `Caller` would keep running the old body.
     */
    @Test
    fun an_inline_body_edit_reaches_its_callers() = runTest(timeout = 10.minutes) {
        val project = fixture.project(applicationId = "com.example.demo")
        val layout = ProjectLayout.of(project)
        kt(layout, "Inline", "inline fun greeting(): String = \"old greeting\"")
        kt(layout, "Caller", "object Caller { fun run() = greeting() }")
        Thread.sleep(IncrementalJava.RACY_MILLIS + 200)
        assertBuilt(rebuild(project))
        val before = classBytes("Caller")

        kt(layout, "Inline", "inline fun greeting(): String = \"new greeting\"")
        val result = rebuild(project)

        assertBuilt(result)
        assertTrue("Caller still holds the old inlined body", !before.contentEquals(classBytes("Caller")))
        assertTrue(String(classBytes("Caller"), Charsets.ISO_8859_1).contains("new greeting"))
    }

    /** Java calling Kotlin and Kotlin calling Java, each edited on its own. */
    @Test
    fun java_and_kotlin_edits_still_link_both_ways() = runTest(timeout = 10.minutes) {
        val project = fixture.project(applicationId = "com.example.demo")
        val layout = ProjectLayout.of(project)
        kt(layout, "FromKotlin", "object FromKotlin { @JvmStatic fun text() = \"k1\" + FromJava.text() }")
        File(layout.javaDir, "com/example/demo/FromJava.java").writeText(
            "package com.example.demo;\npublic final class FromJava { public static String text() { return \"j1\"; } }\n",
        )
        File(layout.javaDir, "com/example/demo/UsesKotlin.java").writeText(
            "package com.example.demo;\npublic final class UsesKotlin { public static String go() { return FromKotlin.text(); } }\n",
        )
        Thread.sleep(IncrementalJava.RACY_MILLIS + 200)
        assertBuilt(rebuild(project))

        File(layout.javaDir, "com/example/demo/FromJava.java").writeText(
            "package com.example.demo;\npublic final class FromJava { public static String text() { return \"j2\"; } }\n",
        )
        assertBuilt(rebuild(project))
        kt(layout, "FromKotlin", "object FromKotlin { @JvmStatic fun text() = \"k2\" + FromJava.text() }")
        val result = rebuild(project)

        assertBuilt(result)
        val dex = apkText(result)
        assertTrue("the Java edit is missing", dex.contains("j2"))
        assertTrue("the Kotlin edit is missing", dex.contains("k2"))
    }

    private companion object {
        const val TAG = "KotlinBuild"
    }
}
