package com.osamu.aide.spike.kotlinls

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike R12, the question that matters: does the Analysis API **answer** on ART?
 * It does — with two shims, which `tools/analysisapi/FINDINGS.md` §12 explains.
 *
 * `AnalysisApiLoadTest` shows the classes load and their relocated references
 * resolve. That is not the same as the API working — a session is built out of
 * a project model, a virtual file system and a module structure, and none of
 * that is exercised by loading a class.
 *
 * The query is deliberately one that parsing cannot fake. Reading a function's
 * *name* needs only a parser; reading its **return type** needs the front end
 * to run, and telling `String` from `kotlin/String` needs it to resolve against
 * the standard library rather than guess from the source text.
 *
 * The work happens in `AnalysisProbe`, compiled against the relocated jars and
 * shipped dexed beside them, because the API is Kotlin DSLs and lambdas that
 * reflection cannot drive readably. This reflects over one method: `String` in,
 * `String` out. See `tools/analysisapi/probe/AnalysisProbe.kt`.
 */
@RunWith(AndroidJUnit4::class)
class AnalysisSessionTest {

    private lateinit var probe: Class<*>
    private lateinit var sourceDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = context.getExternalFilesDir(null)

        val kotlinc = File(staging, KOTLINC_ARCHIVE)
        val analysisApi = File(staging, ANALYSIS_API_ARCHIVE)
        val probeJar = File(staging, PROBE_ARCHIVE)
        assumeTrue("no $KOTLINC_ARCHIVE staged", kotlinc.isFile)
        assumeTrue("no $ANALYSIS_API_ARCHIVE staged", analysisApi.isFile)
        assumeTrue("no $PROBE_ARCHIVE staged", probeJar.isFile)

        val local = File(context.filesDir, "kotlinls").apply { mkdirs() }
        val compilerJar = unpack(kotlinc, File(local, "kotlinc.jar"), "kotlinc.jar")
        val analysisLocal = readOnlyCopy(analysisApi, File(local, ANALYSIS_API_ARCHIVE))
        val probeLocal = readOnlyCopy(probeJar, File(local, PROBE_ARCHIVE))

        // **One loader, not a chain, and this is the finding.** The obvious
        // arrangement -- the Analysis API parented to the compiler -- fails:
        // IntelliJ's `MockComponentManager.loadClass` resolves plugin classes
        // with `Class.forName`, which uses *its own* loader, and the compiler's
        // loader is the parent and cannot see a child. The error is a
        // `ClassNotFoundException` naming a class that is plainly in the dex.
        //
        // Flat, parented to the boot loader for the reason
        // `tools/kotlinc/FINDINGS.md` gives: parenting to the app's loader
        // hands the archive the app's own kotlin-stdlib synthetics.
        val loader = PathClassLoader(
            listOf(compilerJar, analysisLocal, probeLocal)
                .joinToString(":") { it.absolutePath },
            null,
        )
        probe = loader.loadClass("com.osamu.aide.analysisapi.probe.AnalysisProbe")

        sourceDir = File(context.cacheDir, "probe-src-${System.nanoTime()}").apply { mkdirs() }
        File(sourceDir, "Greeting.kt").writeText(
            """
            package probe

            fun greet(name: String): String = "hello " + name

            fun count(items: List<Int>): Int = items.size
            """.trimIndent(),
        )
    }

    private fun readOnlyCopy(source: File, target: File): File {
        if (!target.isFile || target.length() != source.length()) {
            target.delete()
            source.copyTo(target, overwrite = true)
        }
        // A dex file the app can write to will not load at all; see
        // AnalysisApiLoadTest for the exception this avoids.
        target.setWritable(false, false)
        return target
    }

    private fun unpack(archive: File, target: File, entry: String): File {
        if (!target.isFile) {
            java.util.zip.ZipFile(archive).use { zip ->
                val found = zip.getEntry(entry) ?: error("no $entry in ${archive.name}")
                zip.getInputStream(found).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        target.setWritable(false, false)
        return target
    }

    private fun describe(): String {
        val method = probe.getMethod("describeFunctions", String::class.java, String::class.java)
        val started = System.nanoTime()
        val result = method.invoke(null, sourceDir.absolutePath, null) as String
        val millis = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "describeFunctions took $millis ms")
        Log.i(TAG, "result: $result")
        return result
    }

    /**
     * What the loader can see, which rules out the obvious explanation.
     *
     * The descriptors read back through the classloader **in both forms**, with
     * and without a leading slash. That is why the finding below does not blame
     * resource visibility, and why a slash-tolerant loader — tried on the
     * desktop — changes nothing.
     */
    @Test
    fun the_loader_can_read_the_descriptors_as_resources() {
        val result = probe.getMethod("probeResources").invoke(null) as String
        Log.i(TAG, "resources: $result")

        assertTrue(
            "the descriptors are not readable through the loader at all: $result",
            "analysis-api-fir.xml[cl=true" in result,
        )
    }

    /**
     * The probe runs, and reaches the API's own code.
     *
     * Kept separate from the resolution assertions because it is a weaker,
     * different claim — the toolchain is sound as far as *execution*. It was
     * what distinguished "the probe never started" from "the session would not
     * build" while that was still the open question.
     */
    @Test
    fun the_probe_runs_inside_the_analysis_api_archive() {
        val result = describe()

        assertTrue("the probe did not run at all", result.isNotBlank())
        // Reaching the API means failing *inside* it, not before it. Matching
        // on a single package name was too narrow: the Caffeine failure below
        // happens within `StandaloneAnalysisAPISessionBuilder.build`, so the
        // probe plainly got there, and a substring test said otherwise.
        assertTrue(
            "the probe failed before reaching the Analysis API: $result",
            result.startsWith("OK ") ||
                "org.jetbrains.kotlin.analysis" in result ||
                "caffeine" in result,
        )
    }

    /**
     * A session opens and resolves a declaration to its type, **on ART**.
     *
     * `kotlin/String` is the whole point. The source says `String`; only a
     * resolved front end says `kotlin/String`. A parser could produce the
     * function's name; nothing but the K2 front end produces its type.
     */
    @Test
    fun a_session_opens_and_resolves_a_declaration() {
        val result = describe()

        assertTrue("the probe reported a failure: $result", result.startsWith("OK "))
        assertTrue(
            "greet was not resolved to its qualified types: $result",
            "greet(kotlin/String):kotlin/String" in result,
        )
    }

    /**
     * A generic signature resolves too.
     *
     * `List<Int>` separates resolution from string handling: the answer has to
     * be `kotlin/collections/List<kotlin/Int>`, and that appears nowhere in the
     * source text.
     */
    @Test
    fun a_generic_parameter_resolves_to_its_qualified_form() {
        val result = describe()

        assertTrue("the probe reported a failure: $result", result.startsWith("OK "))
        assertTrue(
            "count's List<Int> parameter did not resolve: $result",
            "kotlin/collections/List<kotlin/Int>" in result,
        )
        assertTrue("count's return type did not resolve: $result", "):kotlin/Int" in result)
    }

    /**
     * What the three costs actually are, on a device.
     *
     * **The number M3's budget is about is not the one `describe()` reports.**
     * Every call there builds a whole session and throws it away, so its ~2.2 s
     * is construction — paid once by a resident service, the way `:lsp:java`
     * holds a warm javac. What a user waits for is one query against a session
     * that is already up.
     *
     * The probe separates first-touch resolution from a cache hit because
     * measuring the same symbol twice measures the cache. See `timeQueries`.
     *
     * Asserted loosely and deliberately. The claim worth pinning is the
     * *shape* — that a warm query is orders below session construction, so a
     * resident session is the right design — not a millisecond count on one
     * emulator, which would fail on other hardware for no reason anyone could
     * act on. The measured numbers go to logcat and to FINDINGS; only the shape
     * is a test.
     */
    @Test
    fun a_query_against_a_live_session_costs_far_less_than_building_one() {
        manyFunctions()
        val method = probe.getMethod("timeQueries", String::class.java, Int::class.javaPrimitiveType)
        val result = method.invoke(null, sourceDir.absolutePath, 20) as String
        Log.i(TAG, "timings: $result")

        assertTrue("the timing probe failed: $result", result.startsWith("OK "))
        val build = Regex("build=(\\d+)ms").find(result)!!.groupValues[1].toLong()
        val first = Regex("first=(\\d+)ms").find(result)!!.groupValues[1].toLong()

        assertTrue("session construction was suspiciously cheap: $result", build > 100)
        assertTrue(
            "a first-touch query cost ${first}ms against a ${build}ms build — " +
                "resolution is not cheap relative to construction: $result",
            first < build / 4,
        )
    }

    /**
     * Enough declarations for a median to mean something.
     *
     * Each has a distinct name and a type that has to be resolved rather than
     * copied from the source text, so first-touch resolution is sampled once
     * per declaration instead of once overall.
     */
    private fun manyFunctions() {
        File(sourceDir, "Many.kt").writeText(
            buildString {
                appendLine("package probe")
                appendLine()
                repeat(40) { index ->
                    appendLine("fun pick$index(items: List<String>, at: Int): String = items[at]")
                }
            },
        )
    }

    private companion object {
        const val TAG = "AnalysisSessionTest"
        const val KOTLINC_ARCHIVE = "kotlin-compiler-2.2.10.zip"
        const val ANALYSIS_API_ARCHIVE = "analysis-api-2.2.10.zip"
        const val PROBE_ARCHIVE = "analysis-probe.jar"
    }
}
