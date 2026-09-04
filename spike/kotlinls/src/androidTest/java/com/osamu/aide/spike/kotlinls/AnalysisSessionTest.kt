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
 * Spike R12, the question that matters: does the Analysis API **answer**? It does.
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
     * The ART boundary, pinned. **Delete this when it starts failing.**
     *
     * **On a desktop JVM this same probe, on these same archives, works:**
     *
     * ```
     * OK greet(kotlin/String):kotlin/String
     *    count(kotlin/collections/List<kotlin/Int>):kotlin/Int
     * ```
     *
     * A session opens and resolves `String` to `kotlin/String` and `List<Int>`
     * to `kotlin/collections/List<kotlin/Int>` — neither of which appears in
     * the source text, so that is the front end running, not a parser.
     *
     * On ART it stops in **Caffeine**, which the Analysis API caches with and
     * which the compiler does not bundle. Both versions fail here, for
     * unrelated reasons, and neither is about our relocation:
     *
     * - **3.x** logs through `System.getLogger`, the Java 9 `System.Logger`
     *   API Android has never had — fatal in Caffeine's static initialiser.
     * - **2.x** reaches `Thread.threadLocalRandomProbe` through `Unsafe`, a
     *   JDK-internal field Android's `Thread` does not declare. Thrown from a
     *   static initialiser in `StripedBuffer`, so there is no fallback path.
     *
     * Both are shimmable exactly as `tools/kotlinc/build-kotlinc-dex.py`
     * already shims four compiler classes that "cannot be rescued by
     * renaming". That is the next piece of work, and it is bounded.
     *
     * `tools/analysisapi/FINDINGS.md` §11.
     */
    @Test
    fun a_session_still_stops_at_caffeine_on_art() {
        val result = describe()

        assertTrue(
            "A session built on ART — R12's last blocker is gone. Delete this " +
                "test and assert the real thing, which the desktop already " +
                "produces: greet(kotlin/String):kotlin/String and " +
                "count(kotlin/collections/List<kotlin/Int>):kotlin/Int. Got: $result",
            result.startsWith("ERR "),
        )
        assertTrue(
            "It no longer stops in Caffeine, so FINDINGS.md §11 is out of date " +
                "and should be corrected rather than trusted: $result",
            "caffeine" in result,
        )
    }

    private companion object {
        const val TAG = "AnalysisSessionTest"
        const val KOTLINC_ARCHIVE = "kotlin-compiler-2.2.10.zip"
        const val ANALYSIS_API_ARCHIVE = "analysis-api-2.2.10.zip"
        const val PROBE_ARCHIVE = "analysis-probe.jar"
    }
}
