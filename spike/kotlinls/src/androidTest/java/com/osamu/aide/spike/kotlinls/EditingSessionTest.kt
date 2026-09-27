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
 * The case an editor actually lives in: **the buffer, not the file on disk.**
 *
 * `AnalysisSessionTest` proves a session builds on ART and resolves what it
 * reads from a source root. That is a batch tool. A language service is asked
 * about text that exists only in a buffer, changes on every keystroke, and
 * usually does not parse — and it cannot rebuild the session to answer, because
 * that costs 1808 ms.
 *
 * This is the gate for a `:lsp:kotlin` module. If a dangling file resolves
 * against a resident session on ART, that module is ordinary work; if it does
 * not, its whole design is different, and better to know now.
 *
 * See `tools/analysisapi/probe/EditingProbe.kt` for why the work lives inside
 * the archive rather than here.
 */
@RunWith(AndroidJUnit4::class)
class EditingSessionTest {

    private lateinit var probe: Class<*>
    private lateinit var sourceDir: File

    /**
     * The standard library, as a **library module** for the session.
     *
     * Not the same jar the archive runs on. `kotlinc.jar` holds dex; this is
     * the plain-bytecode `kotlin-stdlib.jar` beside it in the same component,
     * and the Analysis API reads it as a binary root the way a desktop would.
     */
    private lateinit var stdlib: File

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
        stdlib = unpack(kotlinc, File(local, "kotlin-stdlib.jar"), "kotlin-stdlib.jar")
        val analysisLocal = readOnlyCopy(analysisApi, File(local, ANALYSIS_API_ARCHIVE))
        val probeLocal = readOnlyCopy(probeJar, File(local, PROBE_ARCHIVE))

        val loader = PathClassLoader(
            listOf(compilerJar, analysisLocal, probeLocal)
                .joinToString(":") { it.absolutePath },
            null,
        )
        probe = loader.loadClass("com.osamu.aide.analysisapi.probe.EditingProbe")

        // A real source root, because a dangling file is resolved *against a
        // module* — there has to be one for its context to point at.
        sourceDir = File(context.cacheDir, "edit-src-${System.nanoTime()}").apply { mkdirs() }
        File(sourceDir, "OnDisk.kt").writeText(
            """
            package probe

            fun onDisk(): String = "unchanged"
            """.trimIndent(),
        )
        probe.getMethod("release").invoke(null)
    }

    private fun readOnlyCopy(source: File, target: File): File {
        if (!target.isFile || target.length() != source.length()) {
            target.delete()
            source.copyTo(target, overwrite = true)
        }
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

    private fun complete(text: String, offset: Int): String {
        val method = probe.getMethod(
            "completeInMemory",
            String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val started = System.nanoTime()
        val result =
            method.invoke(null, sourceDir.absolutePath, stdlib.absolutePath, text, offset) as String
        Log.i(TAG, "complete took ${(System.nanoTime() - started) / 1_000_000} ms")
        Log.i(TAG, "complete: ${result.take(400)}")
        return result
    }

    /**
     * Completion on a receiver whose text was never written to disk.
     *
     * The buffer declares `local` and asks for its members. Nothing about
     * `local` exists in the source root, so an answer here can only have come
     * from resolving **the buffer** — and `length` and `subSequence` can only
     * have come from resolving it to `kotlin.String` against the standard
     * library.
     */
    @Test
    fun completion_resolves_a_receiver_that_exists_only_in_the_buffer() {
        val result = complete(STRING_RECEIVER, cursorIn(STRING_RECEIVER))

        assertTrue("completion failed: $result", result.startsWith("OK "))
        assertTrue("String.length is missing: $result", " length" in result)
        assertTrue("String.subSequence is missing: $result", " subSequence" in result)
        assertTrue("String.compareTo is missing: $result", " compareTo" in result)
    }

    /**
     * **Extensions are not in the answer, and a user would expect them.**
     *
     * `uppercase` is not a member of `kotlin.String` — it is an extension in
     * `kotlin.text`, and `KaType.scope` is the receiver's *declared member*
     * scope, which does not and should not contain it. So this passes today and
     * the completion above is, from a user's point of view, badly incomplete:
     * eight members where an IDE offers hundreds.
     *
     * Pinned as a test rather than left as a comment because it is the single
     * biggest gap between what the probe does and what `:lsp:kotlin` has to do,
     * and because the fix — collecting callables from the file's imported and
     * package scopes and filtering by applicable receiver type — will make this
     * test fail, which is exactly when someone should be reading it.
     */
    @Test
    fun extensions_are_still_missing_from_completion() {
        val result = complete(STRING_RECEIVER, cursorIn(STRING_RECEIVER))

        assertTrue("completion failed: $result", result.startsWith("OK "))
        assertTrue(
            "uppercase came back, so extensions are being collected — " +
                "delete this test and assert them properly",
            " uppercase" !in result,
        )
    }

    /**
     * What a keystroke costs once the session is up.
     *
     * The first call builds the session; every call after it re-parses and
     * re-resolves the buffer, which is what a language service pays per query.
     * The number goes to logcat and to `tools/analysisapi/FINDINGS.md`; only
     * the shape is asserted, for the reason `AnalysisSessionTest` gives.
     */
    @Test
    fun a_warm_completion_is_far_cheaper_than_the_first_one() {
        val cold = timed(STRING_RECEIVER, cursorIn(STRING_RECEIVER))
        val warm = (1..4).map { timed(STRING_RECEIVER, cursorIn(STRING_RECEIVER)) }.sorted()
        val median = warm[warm.size / 2]
        Log.i(TAG, "completion cold=${cold}ms warm median=${median}ms of ${warm}")

        assertTrue("a warm completion (${median}ms) was no cheaper than the first (${cold}ms)",
            median < cold / 2)
    }

    private fun timed(text: String, offset: Int): Long {
        val method = probe.getMethod(
            "completeInMemory",
            String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val started = System.nanoTime()
        val result =
            method.invoke(null, sourceDir.absolutePath, stdlib.absolutePath, text, offset) as String
        check(result.startsWith("OK ")) { "completion failed: $result" }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun cursorIn(text: String) = text.indexOf("local.") + "local.".length

    /**
     * The buffer wins over the file on disk.
     *
     * `OnDisk.kt` declares `onDisk(): String`. The buffer redeclares nothing —
     * it asks about a local whose type it changed. Answering `Int`'s members
     * here rather than `String`'s is what a stale session would do, and it is
     * the failure that would be invisible in the earlier test.
     */
    @Test
    fun a_second_query_reflects_the_edited_buffer_not_the_first_one() {
        complete(STRING_RECEIVER, cursorIn(STRING_RECEIVER))

        val edited = """
            package probe

            fun edit() {
                val local: Int = 1
                local.
            }
        """.trimIndent()
        val result = complete(edited, cursorIn(edited))

        assertTrue("the edited query failed: $result", result.startsWith("OK "))
        assertTrue("Int.inc is missing, so the edit was not seen: $result", " inc" in result)
        assertTrue(
            "String.uppercase came back for an Int receiver — the session is stale: $result",
            " uppercase" !in result,
        )
    }

    /**
     * Errors before a build, from the buffer.
     *
     * Assigning an `Int` to a `String` is a type error only a resolved front end
     * finds; a parser sees a perfectly good assignment.
     */
    @Test
    fun diagnostics_come_back_for_a_type_error_in_the_buffer() {
        val text = """
            package probe

            fun broken() {
                val wrong: String = 1
            }
        """.trimIndent()
        val method = probe.getMethod(
            "diagnoseInMemory", String::class.java, String::class.java, String::class.java,
        )
        val result = method.invoke(null, sourceDir.absolutePath, stdlib.absolutePath, text) as String
        Log.i(TAG, "diagnostics: ${result.take(400)}")

        assertTrue("diagnostics failed: $result", result.startsWith("OK "))
        assertTrue("no diagnostic was reported for a type error: $result", "OK 0 " !in result)
    }

    /**
     * Clean code reports nothing.
     *
     * Without this, a service that reported an error for every file would pass
     * the test above — and be worse than having no diagnostics at all.
     */
    @Test
    fun clean_code_in_the_buffer_reports_no_diagnostics() {
        val text = """
            package probe

            fun fine(): String = "ok"
        """.trimIndent()
        val method = probe.getMethod(
            "diagnoseInMemory", String::class.java, String::class.java, String::class.java,
        )
        val result = method.invoke(null, sourceDir.absolutePath, stdlib.absolutePath, text) as String
        Log.i(TAG, "clean: ${result.take(400)}")

        assertTrue("diagnostics failed: $result", result.startsWith("OK "))
        assertTrue("clean code produced diagnostics: $result", result.startsWith("OK 0 "))
    }

    /** A diagnostic, printed to logcat; asserts only that it ran. */
    @Test
    fun zz_scope_report() {
        val method = probe.getMethod(
            "scopeReport",
            String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val result = method.invoke(
            null, sourceDir.absolutePath, stdlib.absolutePath,
            STRING_RECEIVER, cursorIn(STRING_RECEIVER),
        ) as String
        Log.i(TAG, "scopes: $result")
        assertTrue(result.isNotBlank())
    }

    /**
     * Does a **package scope** enumerate what the importing scope will not?
     *
     * §16 of `tools/analysisapi/FINDINGS.md` is a wall: the star-importing
     * scope answers only for names it already knows, so `kotlin.text`'s
     * extensions never appear and `String` completes to eight members.
     * `findPackage` + `packageScope` asks the symbol provider, which reads
     * jars, instead of an import scope. If that enumerates, the wall has a door.
     *
     * A diagnostic, printed to logcat; asserts only that it ran.
     */
    @Test
    fun zz_package_scope_report() {
        val method = probe.getMethod(
            "packageScopeReport",
            String::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType, String::class.java,
        )
        for (pkg in listOf("kotlin.text", "kotlin.collections")) {
            val result = method.invoke(
                null, sourceDir.absolutePath, stdlib.absolutePath,
                STRING_RECEIVER, cursorIn(STRING_RECEIVER), pkg,
            ) as String
            Log.i(TAG, "package: $result")
        }
        val index = probe.getMethod(
            "declarationIndexReport",
            String::class.java, String::class.java, String::class.java,
        )
        for (pkg in listOf("kotlin.text", "kotlin.collections", "probe")) {
            val result = index.invoke(
                null, sourceDir.absolutePath, stdlib.absolutePath, pkg,
            ) as String
            Log.i(TAG, "index: $result")
        }
        val facade = probe.getMethod(
            "facadeReport",
            String::class.java, String::class.java, String::class.java, String::class.java,
        )
        for ((pkg, cls) in listOf("kotlin.text" to "StringsKt", "kotlin.collections" to "CollectionsKt")) {
            val result = facade.invoke(
                null, sourceDir.absolutePath, stdlib.absolutePath, pkg, cls,
            ) as String
            Log.i(TAG, "facade: $result")
        }
        val topLevel = probe.getMethod(
            "topLevelCallableReport",
            String::class.java, String::class.java, String::class.java, String::class.java,
        )
        for ((pkg, name) in listOf(
            "kotlin.text" to "uppercase",
            "kotlin.text" to "isBlank",
            "kotlin.collections" to "map",
        )) {
            val result = topLevel.invoke(
                null, sourceDir.absolutePath, stdlib.absolutePath, pkg, name,
            ) as String
            Log.i(TAG, "topLevel: $result")
        }
        assertTrue(true)
    }

    private companion object {
        const val TAG = "EditingSessionTest"

        /** A buffer whose receiver resolves to `kotlin.String`. */
        val STRING_RECEIVER = """
            package probe

            fun edit() {
                val local: String = "x"
                local.
            }
        """.trimIndent()

        const val KOTLINC_ARCHIVE = "kotlin-compiler-2.2.10.zip"
        const val ANALYSIS_API_ARCHIVE = "analysis-api-2.2.10.zip"
        const val PROBE_ARCHIVE = "analysis-probe.jar"
    }
}
