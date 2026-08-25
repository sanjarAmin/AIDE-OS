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
 * **M4's acceptance test, in the plan's own words.**
 *
 * > Project with `androidx.appcompat` + Kotlin sources builds
 *
 * Both halves of M4 had tests before this one and neither test had both halves:
 * `DependencyResolverTest` resolves AndroidX and compiles nothing,
 * `KotlinBuildTest` compiles Kotlin against no dependencies. Separately green,
 * jointly unproven -- and this project has twice shipped exactly that shape of
 * bug, where every module's own suite passed and the combination did not
 * (`engine/fast/FINDINGS.md` section 10 is the sharpest example).
 *
 * So the Kotlin here deliberately *uses* the dependency rather than merely
 * coexisting with it. A file that compiled fine beside appcompat without
 * referring to it would prove nothing about the classpath reaching kotlinc.
 *
 * This exercises, in one build: Maven resolution, AAR extraction, aapt2
 * compiling and overlaying a library's resources, R generation across that
 * overlay, kotlinc against a dependency classpath, ECJ after it, and D8 dexing
 * the lot.
 */
@RunWith(AndroidJUnit4::class)
class AndroidXKotlinBuildTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var kotlin: KotlinCompiler
    private lateinit var resolver: DependencyResolver

    @Before
    fun setUp() {
        fixture = EngineTestFixture("androidx-kotlin-test")
        fixture.assumeAapt2Supported()

        val archive = stage("kotlinc.jar")
        val stdlib = stage("kotlin-stdlib.jar")
        assumeTrue("kotlinc.jar is not staged; see tools/kotlinc/FINDINGS.md", archive != null)
        assumeTrue("kotlin-stdlib.jar is not staged", stdlib != null)

        kotlin = KotlinCompiler(
            KotlinToolchain(archive!!, stdlib!!),
            File(fixture.context.cacheDir, "kotlin-host"),
        )
        // Shared with the other suites on purpose: re-downloading AndroidX per
        // test class would make this unrunnable.
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
    fun a_kotlin_project_using_appcompat_builds() = runTest(timeout = 10.minutes) {
        val resolved = when (
            val result = resolver.resolve(
                listOf(requireNotNull(Coordinate.parse("androidx.appcompat:appcompat:1.7.0"))),
            )
        ) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> throw AssertionError("resolution failed: ${result.error.message}")
        }
        assumeTrue("dependencies did not resolve; no network?", resolved.dependencies.isNotEmpty())
        Log.i(TAG, "resolved ${resolved.dependencies.size} artifacts")

        val project = fixture.project(
            applicationId = "com.example.demo",
            language = SourceLanguage.KOTLIN,
        )
        val layout = ProjectLayout.of(project)

        // Kotlin that actually depends on AndroidX. Extending AppCompatActivity
        // makes kotlinc resolve the AAR's classes.jar, and referring to a
        // library resource makes the R class span the overlay.
        File(layout.javaDir, "com/example/demo/KotlinScreen.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example.demo

                import android.os.Bundle
                import androidx.appcompat.app.AppCompatActivity

                class KotlinScreen : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        title = getString(R.string.greeting)
                    }

                    fun mode(): Int = getDelegate().localNightMode
                }
                """.trimIndent(),
            )
        }

        val events = engineWith(resolved.compileClasspath, resolved.resourceDirectories)
            .build(
                BuildRequest(
                    project = project,
                    outputDir = File(fixture.workDir, "build"),
                    dependencies = DependencyInputs(
                        classpath = resolved.compileClasspath,
                        resourceDirectories = resolved.resourceDirectories,
                    ),
                ),
            )
            .toList()

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

        // The APK has to carry appcompat's code as well as the user's, or the
        // app links at compile time and dies on the device.
        val apk = (result as BuildResult.Success).apk
        val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("no dex in the APK: $entries", entries.any { it.endsWith(".dex") })
        assertTrue("no resource table in the APK: $entries", "resources.arsc" in entries)
        Log.i(TAG, "built ${apk.name}: ${apk.length()} bytes, ${entries.size} entries")
    }

    private fun engineWith(classpath: List<File>, resources: List<File>) = FastBuildSystem(
        fixture.runner,
        fixture.platform,
        DefaultDispatcherProvider(),
        kotlin,
    ).also {
        Log.i(TAG, "classpath ${classpath.size} jars, ${resources.size} resource dirs")
    }

    private companion object {
        const val TAG = "AndroidXKotlin"
    }
}
