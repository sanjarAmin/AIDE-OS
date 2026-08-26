package com.osamu.aide.engine.fast

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.DependencyInputs
import com.osamu.aide.engine.deps.Coordinate
import com.osamu.aide.engine.deps.DependencyResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes

/**
 * **M6's acceptance test: a Compose project builds on the device.**
 *
 * The compiler work for this was done by spike R2 -- the Compose plugin is
 * dexed beside the compiler in `kotlinc.jar`, and `KotlinCompiler` registers it
 * with `-Xplugin` when it finds `Composer` on the classpath. What was missing
 * was any test that the whole path works from a project on disk.
 *
 * **A clean build proves nothing here**, and that is the whole reason this file
 * is careful. R2's finding 7: plugin discovery reads `META-INF/services` out of
 * the file named on the command line, so an unregistered plugin produces no
 * error, no warning, and valid bytecode that simply was not transformed. A test
 * asserting "the build succeeded" would pass with Compose completely inert.
 *
 * So the assertion is on the **transformed bytecode**: a `@Composable` function
 * compiled with the plugin gains a `Composer` parameter, and the class file
 * therefore references `androidx/compose/runtime/Composer` even though the
 * source never names it. Compiled without the plugin, that reference does not
 * exist. This is the same observable effect R2 used, checked one layer further
 * out -- through the engine rather than through a bare compiler call.
 */
@RunWith(AndroidJUnit4::class)
class ComposeBuildTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var kotlin: KotlinCompiler
    private lateinit var resolver: DependencyResolver

    @Before
    fun setUp() {
        fixture = EngineTestFixture("compose-test")
        fixture.assumeAapt2Supported()

        val archive = stage("kotlinc.jar")
        val stdlib = stage("kotlin-stdlib.jar")
        assumeTrue("kotlinc.jar is not staged; see tools/kotlinc/FINDINGS.md", archive != null)
        assumeTrue("kotlin-stdlib.jar is not staged", stdlib != null)

        kotlin = KotlinCompiler(
            KotlinToolchain(archive!!, stdlib!!),
            File(fixture.context.cacheDir, "kotlin-host"),
        )
        resolver = DependencyResolver(
            File(fixture.context.cacheDir, "maven"),
            DefaultDispatcherProvider(),
        )
    }

    private fun stage(name: String): File? {
        val target = File(fixture.workDir, name)
        if (target.isFile) return target
        return runCatching {
            fixture.context.assets.open(name).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            // API 29+ refuses to load a dex the app can still write to.
            target.setReadOnly()
            target
        }.getOrNull()
    }

    @Test
    fun a_compose_project_builds_and_its_composables_are_transformed() =
        runTest(timeout = 15.minutes) {
            val resolved = when (
                val result = resolver.resolve(
                    listOf(
                        requireNotNull(
                            Coordinate.parse("androidx.compose.runtime:runtime-android:1.9.0"),
                        ),
                    ),
                )
            ) {
                is AppResult.Success -> result.value
                is AppResult.Failure ->
                    throw AssertionError("resolution failed: ${result.error.message}")
            }
            assumeTrue("dependencies did not resolve; no network?", resolved.dependencies.isNotEmpty())
            Log.i(TAG, "resolved ${resolved.dependencies.size} artifacts")

            val project = fixture.project(
                applicationId = "com.example.composedemo",
                language = SourceLanguage.KOTLIN,
            )
            val layout = ProjectLayout.of(project)

            // Deliberately minimal, and deliberately never naming Composer. The
            // whole assertion below rests on the plugin putting a reference
            // there that this source does not contain.
            File(layout.javaDir, "com/example/composedemo/Greeting.kt").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    package com.example.composedemo

                    import androidx.compose.runtime.Composable

                    @Composable
                    fun Greeting(name: String) {
                        Wrapper {
                            Label("Hello, ${'$'}name")
                        }
                    }

                    @Composable
                    fun Wrapper(content: @Composable () -> Unit) = content()

                    @Composable
                    fun Label(text: String) {
                        check(text.isNotEmpty())
                    }
                    """.trimIndent(),
                )
            }

            val outputDir = File(fixture.workDir, "build")
            val events = FastBuildSystem(
                fixture.runner,
                fixture.platform,
                DefaultDispatcherProvider(),
                kotlin,
            ).build(
                BuildRequest(
                    project = project,
                    outputDir = outputDir,
                    dependencies = DependencyInputs(
                        classpath = resolved.compileClasspath,
                        resourceDirectories = resolved.resourceDirectories,
                    ),
                ),
            ).toList()

            val result = (events.last() as BuildEvent.Finished).result
            assertTrue(
                "build failed: ${(result as? BuildResult.Failure)?.message}\n" +
                    result.diagnostics.joinToString("\n") { it.describe() },
                result is BuildResult.Success,
            )
            assertTrue(
                "the Kotlin stage never ran",
                events.filterIsInstance<BuildEvent.StageStarted>()
                    .any { it.stage == BuildStage.COMPILE_KOTLIN },
            )

            // The assertion this test exists for.
            val greeting = outputDir.walkTopDown()
                .firstOrNull { it.name == "GreetingKt.class" }
            assertTrue("no compiled class for Greeting.kt under $outputDir", greeting != null)

            val bytes = greeting!!.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(
                "the Compose plugin did not run: GreetingKt has no Composer reference, " +
                    "which means @Composable was left as a plain annotation. " +
                    "tools/kotlinc/FINDINGS.md finding 7 -- the plugin is registered by " +
                    "filename, and a missing registration is silent.",
                "androidx/compose/runtime/Composer" in bytes,
            )

            val apk = (result as BuildResult.Success).apk
            val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
            assertTrue("no dex in the APK: $entries", entries.any { it.endsWith(".dex") })
            Log.i(TAG, "built ${apk.name}: ${apk.length()} bytes")
        }

    private companion object {
        const val TAG = "ComposeBuild"
    }
}
