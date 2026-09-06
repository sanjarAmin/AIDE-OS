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

    /**
     * Two Kotlin files in one project, each answering about itself.
     *
     * `tools/analysisapi/FINDINGS.md` §24 has said since the module was written
     * that only one file in one project had ever been exercised. The session is
     * resident and shared, and each request builds a *dangling* file for the
     * buffer it was given, so answering about the wrong one is a plausible
     * failure -- and the sibling's declarations must still resolve, because
     * they are in the same source root.
     */
    @Test
    fun two_kotlin_files_each_answer_about_themselves() = runBlocking {
        val service = services.serviceFor(sourceFile(), projectRoot!!, emptyList())
        assumeTrue("no Kotlin service; components not installed", service != null)

        val dir = File(projectRoot!!, "src/main/kotlin/com/example")
        val first = File(dir, "First.kt")
        val second = File(dir, "Second.kt")
        first.writeText("package com.example\n\nfun declaredInFirst(): Int = 1\n")
        second.writeText("package com.example\n\nfun declaredInSecond(): Int = 2\n")

        val askFirst = "package com.example\n\nfun a() {\n    declaredIn\n}"
        val askSecond = "package com.example\n\nfun b() {\n    declaredIn\n}"
        val cursor = { t: String -> t.lastIndexOf("declaredIn") + "declaredIn".length }

        val fromFirst = service!!.complete(first, askFirst, cursor(askFirst)).map { it.label }
        val fromSecond = service.complete(second, askSecond, cursor(askSecond)).map { it.label }
        // Back to the first, because a service that answered correctly once and
        // then cached the wrong buffer would pass a one-way test.
        val backToFirst = service.complete(first, askFirst, cursor(askFirst)).map { it.label }
        Log.i(TAG, "first=$fromFirst second=$fromSecond back=$backToFirst")

        for ((where, labels) in listOf("first" to fromFirst, "second" to fromSecond, "back" to backToFirst)) {
            assertTrue(
                "both siblings share a source root, so both declarations should be " +
                    "visible from $where: $labels",
                labels.any { it.startsWith("declaredInFirst") } &&
                    labels.any { it.startsWith("declaredInSecond") },
            )
        }
    }

    /**
     * A Kotlin tab and a Java tab, each routed to its own service.
     *
     * `LanguageServices` picks per *file*, and its own comment records why: a
     * completion source that captured one service would answer for both tabs
     * with whichever it happened to be handed. Both languages resolve against
     * `android.jar`, so this also says the two services coexist -- javac warm in
     * one and an Analysis API session in the other, in one process.
     */
    @Test
    fun a_kotlin_tab_and_a_java_tab_get_different_services() = runBlocking {
        val kotlinFile = File(projectRoot!!, "src/main/kotlin/com/example/Main.kt")
            .apply { parentFile?.mkdirs(); writeText("package com.example\n") }
        val javaFile = File(projectRoot!!, "src/main/java/com/example/Legacy.java")
            .apply { parentFile?.mkdirs(); writeText("package com.example;\npublic class Legacy {}\n") }

        val forKotlin = services.serviceFor(kotlinFile, projectRoot!!, emptyList())
        val forJava = services.serviceFor(javaFile, projectRoot!!, emptyList())
        assumeTrue("no Kotlin service; components not installed", forKotlin != null)
        assumeTrue("no Java service; no platform", forJava != null)

        Log.i(TAG, "kotlin -> ${forKotlin!!.javaClass.simpleName}, java -> ${forJava!!.javaClass.simpleName}")
        assertTrue("the .kt file was not routed to the Kotlin service", forKotlin.handles(kotlinFile))
        assertTrue("the .java file was not routed to the Java service", forJava.handles(javaFile))
        assertTrue(
            "one service answered for both tabs: ${forKotlin.javaClass.simpleName}",
            forKotlin.javaClass != forJava.javaClass,
        )

        // And both still answer, in the same process, one after the other.
        val kt = "package com.example\n\nfun f() {\n    val s: String = \"x\"\n    s.upperc\n}"
        val ktLabels = forKotlin.complete(kotlinFile, kt, kt.lastIndexOf("s.upperc") + "s.upperc".length)
            .map { it.label }
        Log.i(TAG, "kotlin tab: $ktLabels")
        assertTrue("the Kotlin tab stopped answering: $ktLabels", ktLabels.any { it.startsWith("uppercase") })
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
