package com.osamu.aide.ui.workspace

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * The whole Kotlin path, joined up: Maven to AAR to session to completion.
 *
 * `:lsp:kotlin`'s own suite proves the session handles an AAR, but it hands one
 * over as a `classes.jar` the *build* unpacked and staged. `tools/analysisapi/FINDINGS.md`
 * §26 was explicit that this left the last join unproven: an AAR that
 * `:engine:deps` resolves and unpacks **at runtime**, reaching a Kotlin session
 * through `LanguageServices`, which is what actually happens when somebody opens
 * a Kotlin file in a project with dependencies.
 *
 * This is the Kotlin twin of [AndroidXCompletionTest], which asks the same
 * question of the Java service, and it reaches the network on a cold cache.
 *
 * **It skips rather than fails without the toolchain.** The Kotlin components
 * are 60 MB and are staged by `gradle/stage-device-archives.gradle.kts`; with
 * no session there is nothing to complete against, and a red test would say the
 * wrong thing.
 */
@RunWith(AndroidJUnit4::class)
class KotlinAndroidXCompletionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private var projectRoot: File? = null
    private lateinit var services: LanguageServices
    private lateinit var dependencies: ProjectDependencies

    @Before
    fun setUp() {
        // The platform, from this suite's own assets, exactly as the Java twin
        // stages it.
        val platform = File(context.filesDir, "toolchains/platforms-android-36/android.jar")
        if (!platform.isFile) {
            platform.parentFile?.mkdirs()
            runCatching {
                InstrumentationRegistry.getInstrumentation().context.assets.open("android.jar")
                    .use { input -> platform.outputStream().use { input.copyTo(it) } }
            }
        }
        assumeTrue("no android.jar staged", platform.isFile)

        // The Kotlin components, into the layout ToolchainStorage expects:
        // one directory per component id, under filesDir/toolchains.
        // The **app under test's** external files, not the instrumentation's:
        // `getInstrumentation().context.getExternalFilesDir(null)` is null here,
        // and a null staging directory reads as "not staged" for ever.
        val staging = context.getExternalFilesDir(null)
        val compiler = File(context.filesDir, "toolchains/kotlin-compiler")
        val analysis = File(context.filesDir, "toolchains/kotlin-analysis-api")
        install(File(staging, "kotlin-compiler-2.2.10.zip"), compiler, "kotlinc.jar", "kotlin-stdlib.jar")
        install(File(staging, "kotlin-analysis-2.2.10.zip"), analysis, "analysis-api.jar", "analysis-backend.jar")
        assumeTrue(
            "the Kotlin components are not staged in $staging, which holds " +
                "${staging?.list()?.toList()}",
            File(compiler, "kotlinc.jar").isFile && File(analysis, "analysis-api.jar").isFile,
        )

        projectRoot = File(context.cacheDir, "kotlin-androidx-${System.nanoTime()}").apply {
            File(this, "src/main/kotlin/com/example").mkdirs()
        }
        services = LanguageServices(
            native = NativeToolchainProvider(context, dispatchers),
            toolchain = ToolchainManager(context, dispatchers),
            dispatchers = dispatchers,
            buildOutputRoot = File(context.cacheDir, "builds-kotlin-test"),
        )
        dependencies = ProjectDependencies(
            DependencyResolver(File(context.cacheDir, "maven"), dispatchers),
        )
    }

    @After
    fun tearDown() {
        if (::services.isInitialized) services.release()
        projectRoot?.deleteRecursively()
    }

    /** Extracts named entries, re-extracting when the archive has moved on. */
    private fun install(archive: File, into: File, vararg entries: String) {
        if (!archive.isFile) return
        into.mkdirs()
        ZipFile(archive).use { zip ->
            for (entry in entries) {
                val found = zip.getEntry(entry) ?: continue
                val target = File(into, entry)
                // Size, for the reason `:lsp:kotlin`'s harness uses it: internal
                // storage survives an `install -r`, so a stale copy is the case
                // worth catching.
                if (target.isFile && target.length() == found.size) continue
                zip.getInputStream(found).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun project() = Project(
        name = "Kotlin AndroidX Demo",
        rootDir = projectRoot!!,
        applicationId = "com.example.ktx",
        language = SourceLanguage.KOTLIN,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
        dependencies = listOf("androidx.core:core-ktx:1.16.0"),
    )

    private fun sourceFile() =
        File(projectRoot!!, "src/main/kotlin/com/example/Main.kt").apply { parentFile?.mkdirs() }

    /**
     * An extension from a resolved AAR completes, through the real wiring.
     *
     * `doOnLayout` is declared in `androidx.core:core-ktx`, an artifact nothing
     * in this test staged: `:engine:deps` fetched it from Maven, unpacked the
     * AAR, and handed the `classes.jar` to `LanguageServices`, which put it on a
     * Kotlin session's classpath behind `android.jar`. Every one of those steps
     * has its own test; this is the only one that asserts they meet.
     */
    @Test
    fun an_extension_from_a_resolved_aar_completes() = runBlocking {
        val classpath = dependencies.classpathFor(project())
        assumeTrue("dependencies did not resolve; no network?", classpath.isNotEmpty())
        Log.i(TAG, "classpath: ${classpath.size} jars")

        val service = services.serviceFor(sourceFile(), projectRoot!!, classpath)
        assumeTrue("no Kotlin service; components not installed", service != null)

        val text = SOURCE.replace(CURSOR, "")
        // Last occurrence: an import line can contain the same text as the call
        // it enables, and matching the first put the caret inside the import.
        // tools/analysisapi/FINDINGS.md section 26.
        val offset = SOURCE.lastIndexOf(CURSOR)

        val labels = service!!.complete(sourceFile(), text, offset).map { it.label }
        Log.i(TAG, "kotlin androidx proposals: ${labels.take(12)}")

        assertTrue(
            "doOnLayout comes from the core-ktx AAR that :engine:deps resolved, " +
                "and did not reach completion: $labels",
            labels.any { it.startsWith("doOnLayout") },
        )
    }

    private companion object {
        const val TAG = "KotlinAndroidXCompletion"
        const val CURSOR = "/*^*/"

        val SOURCE = """
            package com.example

            import android.view.View
            import androidx.core.view.*

            fun edit(view: View) {
                view.doOnL/*^*/
            }
        """.trimIndent()
    }
}
