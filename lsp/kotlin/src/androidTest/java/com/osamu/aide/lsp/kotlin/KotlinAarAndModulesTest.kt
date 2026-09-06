package com.osamu.aide.lsp.kotlin

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
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
 * The two things `tools/analysisapi/FINDINGS.md` §24 said were untried: an
 * **AAR** on the session's classpath, and a reference **across modules**.
 *
 * §21 claimed a project's AARs were covered "by construction" -- the metadata
 * scan takes whatever jars the session is given, and an AAR's `classes.jar` is
 * a jar. §24 was honest that this was the claim and not the evidence: every
 * measurement had been against `kotlin-stdlib`, which is the one jar in the
 * world most likely to be special-cased by an API that ships with the compiler.
 *
 * `androidx.core:core-ktx` is the subject. It is small, it is Kotlin, and
 * almost everything in it is an extension function on a platform type -- so it
 * exercises the index and the applicability checker together, against a
 * receiver that only `android.jar` can resolve. That pairing is what the app
 * actually builds: [KotlinLanguageService.classpath] is the platform followed
 * by the project's dependencies.
 *
 * Both jars are staged by `gradle/stage-device-archives.gradle.kts`, the AAR
 * unpacked from a Maven artifact Gradle resolves, so this reproduces on a clean
 * machine rather than reading someone's cache.
 */
@RunWith(AndroidJUnit4::class)
class KotlinAarAndModulesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var archives: KotlinArchives
    private lateinit var classpath: List<File>
    private var projectRoot: File? = null
    private var service: KotlinLanguageService? = null

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val compiler = Dispatchers.Default
    }

    @Before
    fun setUp() {
        assumeTrue("the archives are dexed at API 30", KotlinArchives.isSupported)
        val staging = context.getExternalFilesDir(null)

        val unpacked = File(context.filesDir, "kotlin-lsp-staging").apply { mkdirs() }
        File(staging, "kotlin-compiler-2.2.10.zip").takeIf { it.isFile }?.let {
            extract(it, "kotlinc.jar", File(unpacked, "kotlinc.jar"))
            extract(it, "kotlin-stdlib.jar", File(unpacked, "kotlin-stdlib.jar"))
        }
        File(staging, "kotlin-analysis-2.2.10.zip").takeIf { it.isFile }?.let {
            extract(it, "analysis-api.jar", File(unpacked, "analysis-api.jar"))
            extract(it, "analysis-backend.jar", File(unpacked, "analysis-backend.jar"))
        }

        archives = KotlinArchives(
            compilerJar = File(unpacked, "kotlinc.jar"),
            stdlibJar = File(unpacked, "kotlin-stdlib.jar"),
            analysisApiJar = File(unpacked, "analysis-api.jar"),
            backendJar = File(unpacked, "analysis-backend.jar"),
            workingDir = File(context.filesDir, "kotlin-lsp"),
        )
        assumeTrue("the Kotlin analysis archives are not staged", archives.isComplete)

        val platform = File(staging, "android.jar")
        val aar = File(staging, "core-ktx.jar")
        assumeTrue(
            "android.jar and core-ktx.jar are not staged in $staging",
            platform.isFile && aar.isFile,
        )
        // Platform first, then the dependency -- the order LanguageServices uses.
        classpath = listOf(platform, aar)
    }

    @After
    fun tearDown() {
        service?.close()
        service = null
        projectRoot?.deleteRecursively()
    }

    private fun extract(archive: File, entry: String, target: File) {
        ZipFile(archive).use { zip ->
            val found = zip.getEntry(entry) ?: error("no $entry in ${archive.name}")
            if (target.isFile && target.length() == found.size) return
            zip.getInputStream(found).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** A single-module project laid out the way the templates lay one out. */
    private fun singleModuleProject(): File {
        val root = File(context.cacheDir, "aar-project-${System.nanoTime()}")
        File(root, "src/main/kotlin").mkdirs()
        projectRoot = root
        return root
    }

    /**
     * The caret after the **last** occurrence of [snippet].
     *
     * `indexOf` is a trap here and cost real time: an import line contains the
     * same text as the call it enables, so `indexOf("view.doOnL")` in a buffer
     * that also says `import androidx.core.view.doOnLayout` puts the caret
     * inside the import. The backend then sees a receiver of `androidx.core.view`
     * -- a package, whose `expressionType` is null -- and correctly answers
     * nothing, which reads exactly like completion being broken. The usage is
     * always last in these buffers.
     */
    private fun cursorAfter(text: String, snippet: String): Int =
        text.lastIndexOf(snippet).also { require(it >= 0) { "no '$snippet' in buffer" } } +
            snippet.length

    private fun open(root: File): KotlinLanguageService =
        KotlinLanguageService(archives, root, dispatchers, classpath)
            .also { service = it }

    // -- the AAR ------------------------------------------------------------

    /**
     * An extension function declared in an **AAR** is offered.
     *
     * `doOnLayout` is `androidx.core.view.ViewKt`'s, on `android.view.View`.
     * The **star** import is load-bearing; see
     * [an_extension_imported_by_name_is_not_offered] for why.
     * Answering it requires all of it to work at once: `android.jar` resolving
     * the receiver, the metadata scan reading `core-ktx`'s
     * `.kotlin_module` and its `@kotlin.Metadata` protobufs, the index
     * narrowing by prefix, and the applicability checker agreeing the extension
     * applies. Nothing in a parser could produce it.
     */
    @Test
    fun an_extension_declared_in_an_aar_is_offered() = runBlocking {
        val service = open(singleModuleProject())
        val text = """
            package sample

            import android.view.View
            import androidx.core.view.*

            fun edit(view: View) {
                view.doOnL
            }
        """.trimIndent()
        val offset = cursorAfter(text, "view.doOnL")

        val labels = service.complete(File(projectRoot!!, "src/main/kotlin/Sample.kt"), text, offset)
            .map { it.label }
        Log.i(TAG, "aar extension proposals: $labels")

        assertTrue(
            "doOnLayout, declared in the core-ktx AAR, was not offered: $labels",
            labels.any { it.startsWith("doOnLayout") },
        )
    }

    /**
     * And an extension the AAR does **not** declare for that receiver is not.
     *
     * Without this the test above passes for a service that offers every name
     * it has ever seen. `getSystemService` is `ContextKt`'s, in the same AAR
     * and the same scan, and a `View` is not a `Context`.
     */
    @Test
    fun an_aar_extension_that_does_not_apply_is_withheld() = runBlocking {
        val service = open(singleModuleProject())
        val text = """
            package sample

            import android.view.View

            fun edit(view: View) {
                view.getSystem
            }
        """.trimIndent()
        val offset = cursorAfter(text, "view.getSystem")

        val labels = service.complete(File(projectRoot!!, "src/main/kotlin/Sample.kt"), text, offset)
            .map { it.label }
        Log.i(TAG, "withheld-extension proposals: $labels")

        assertTrue(
            "getSystemService extends Context, not View, but was offered: $labels",
            labels.none { it.startsWith("getSystemService") },
        )
    }

    /**
     * An extension imported **by name** resolves but is not offered. A gap.
     *
     * `indexedTopLevel` narrows the index to packages that are visible, and
     * builds that list from Kotlin's default imports plus the file's
     * `isAllUnder` directives -- star imports. A single-name import
     * (`import androidx.core.view.doOnLayout`) names a *callable*, not a
     * package, so it contributes nothing and the index is never consulted for
     * it.
     *
     * The symbol is perfectly reachable: with that import the front end
     * typechecks `view.marginStart` as an `Int`, which
     * [a_type_error_against_an_aar_type_is_reported] asserts. Only completion
     * cannot see it. So the user who writes the import by hand, or accepts one
     * from an IDE, then finds the thing they imported missing from the list.
     *
     * Recorded as a test rather than a comment because it is a real limitation
     * with a small fix -- take the parent package of a single-name import as
     * visible too -- and the fix belongs in the backend inside the dex archive,
     * which is a heavier change than this test.
     */
    @Test
    fun an_extension_imported_by_name_is_offered() = runBlocking {
        val service = open(singleModuleProject())
        val text = """
            package sample

            import android.view.View
            import androidx.core.view.doOnLayout

            fun edit(view: View) {
                view.doOnL
            }
        """.trimIndent()
        val offset = cursorAfter(text, "view.doOnL")

        val labels = service.complete(File(projectRoot!!, "src/main/kotlin/Sample.kt"), text, offset)
            .map { it.label }
        Log.i(TAG, "by-name-import proposals: $labels")
        assertTrue(
            "doOnLayout was imported by name and still not offered: $labels",
            labels.any { it.startsWith("doOnLayout") },
        )
    }

    /** A type from the AAR resolves well enough to report a type error. */
    @Test
    fun a_type_error_against_an_aar_type_is_reported() = runBlocking {
        val service = open(singleModuleProject())
        val text = """
            package sample

            import android.view.View
            import androidx.core.view.marginStart

            fun edit(view: View) {
                val wrong: String = view.marginStart
            }
        """.trimIndent()

        val diagnostics = service.diagnostics(File(projectRoot!!, "src/main/kotlin/Sample.kt"), text)
        Log.i(TAG, "aar diagnostics: ${diagnostics.map { it.message }}")

        assertTrue(
            "assigning an Int extension property to a String should not typecheck: " +
                "${diagnostics.map { it.message }}",
            diagnostics.any { it.message.contains("String") && it.message.contains("Int") },
        )
    }

    // -- across modules -----------------------------------------------------

    /**
     * A reference from one module's source to another's resolves.
     *
     * **And the reason is worth knowing, because it is not module support.**
     * `ensureOpen` looks for `src/main/java` and `src/main/kotlin` *directly
     * under the project root* and, finding neither in a multi-module layout,
     * falls back to the root itself as a single source root. So both modules'
     * sources land in one module and every reference between them resolves --
     * including ones Gradle would reject, because nothing here knows that
     * `:app` depends on `:lib` rather than the other way round.
     *
     * That is the right trade for an editor, whose job is to answer about the
     * buffer rather than to enforce a build graph, and the build itself will
     * still refuse an undeclared dependency. It is recorded because a reader
     * would otherwise assume module boundaries were being honoured.
     */
    @Test
    fun a_reference_into_another_module_resolves() = runBlocking {
        val root = File(context.cacheDir, "multi-module-${System.nanoTime()}")
        projectRoot = root
        val lib = File(root, "lib/src/main/kotlin/lib").apply { mkdirs() }
        val app = File(root, "app/src/main/kotlin/app").apply { mkdirs() }
        File(lib, "Greeter.kt").writeText(
            """
            package lib

            class Greeter {
                fun greet(name: String): String = "hi ${'$'}name"
            }
            """.trimIndent(),
        )
        val caller = File(app, "Caller.kt")
        val text = """
            package app

            import lib.Greeter

            fun call() {
                val greeter = Greeter()
                greeter.gre
            }
        """.trimIndent()
        caller.writeText(text)

        val service = open(root)
        val offset = cursorAfter(text, "greeter.gre")
        val labels = service.complete(caller, text, offset).map { it.label }
        Log.i(TAG, "cross-module proposals: $labels")

        assertTrue(
            "greet(), declared in the lib module, did not resolve from app: $labels",
            labels.any { it.startsWith("greet(") },
        )
    }

    /** And the compiler front end agrees the call typechecks. */
    @Test
    fun a_call_across_modules_produces_no_diagnostic() = runBlocking {
        val root = File(context.cacheDir, "multi-module-${System.nanoTime()}")
        projectRoot = root
        File(root, "lib/src/main/kotlin/lib").mkdirs()
        val app = File(root, "app/src/main/kotlin/app").apply { mkdirs() }
        File(root, "lib/src/main/kotlin/lib/Greeter.kt").writeText(
            """
            package lib

            class Greeter {
                fun greet(name: String): String = "hi ${'$'}name"
            }
            """.trimIndent(),
        )
        val caller = File(app, "Caller.kt")
        val text = """
            package app

            import lib.Greeter

            fun call(): String = Greeter().greet("world")
        """.trimIndent()
        caller.writeText(text)

        val service = open(root)
        val diagnostics = service.diagnostics(caller, text)
        Log.i(TAG, "cross-module diagnostics: ${diagnostics.map { it.message }}")

        assertTrue(
            "a correct call into another module was reported as an error: " +
                "${diagnostics.map { it.message }}",
            diagnostics.none { it.severity.name == "ERROR" },
        )
    }

    private companion object {
        const val TAG = "KotlinAarAndModules"
    }
}
