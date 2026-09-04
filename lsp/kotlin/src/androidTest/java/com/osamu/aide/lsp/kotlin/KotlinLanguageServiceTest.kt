package com.osamu.aide.lsp.kotlin

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.lsp.api.CompletionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `:lsp:kotlin` as the editor sees it — through `LanguageService`, not
 * reflection.
 *
 * The spike proved the Analysis API answers on ART. This proves the *module*
 * does: that the wire format survives the classloader boundary, that a
 * `Diagnostic` comes back with a usable line and a relative path, and that a
 * `CompletionItem` comes back with a kind the editor can draw an icon for.
 *
 * Every assertion is on something a parser could not produce. A completion list
 * containing `length` for a `String` and `inc` for an `Int` is resolution; a
 * diagnostic reading "expected 'String', actual 'Int'" is the front end.
 */
@RunWith(AndroidJUnit4::class)
class KotlinLanguageServiceTest {

    private lateinit var service: KotlinLanguageService
    private lateinit var projectRoot: File
    private lateinit var source: File
    private lateinit var archives: KotlinArchives
    private lateinit var dispatchers: DispatcherProvider

    @Before
    fun setUp() {
        assumeTrue("the archives are dexed at API 30", KotlinArchives.isSupported)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = context.getExternalFilesDir(null)

        // Staged as the two components install them: the compiler's two jars
        // extracted from its zip, and the analysis component's two beside them.
        // When the release exists this becomes ToolchainManager.kotlinAnalysisArchives().
        val unpacked = File(context.filesDir, "kotlin-lsp-staging").apply { mkdirs() }
        val compilerZip = File(staging, "kotlin-compiler-2.2.10.zip")
        if (compilerZip.isFile) {
            extract(compilerZip, "kotlinc.jar", File(unpacked, "kotlinc.jar"))
            extract(compilerZip, "kotlin-stdlib.jar", File(unpacked, "kotlin-stdlib.jar"))
        }
        val componentZip = File(staging, "kotlin-analysis-2.2.10.zip")
        if (componentZip.isFile) {
            extract(componentZip, "analysis-api.jar", File(unpacked, "analysis-api.jar"))
            extract(componentZip, "analysis-backend.jar", File(unpacked, "analysis-backend.jar"))
            extract(
                componentZip,
                "top-level-callables.index",
                File(unpacked, "top-level-callables.index"),
            )
        }

        archives = KotlinArchives(
            compilerJar = File(unpacked, "kotlinc.jar"),
            stdlibJar = File(unpacked, "kotlin-stdlib.jar"),
            analysisApiJar = File(unpacked, "analysis-api.jar"),
            backendJar = File(unpacked, "analysis-backend.jar"),
            nameIndex = File(unpacked, "top-level-callables.index"),
            workingDir = File(context.filesDir, "kotlin-lsp"),
        )
        // **Not `assumeTrue` on a message alone.** This suite is worthless if
        // it skips, and this project has already lost time to a suite that
        // silently did. The archives are staged out of band until
        // :toolchain:manager has a component for them; when it does, this
        // becomes a download rather than an assumption.
        assumeTrue(
            "the Kotlin analysis archives are not staged in ${staging?.absolutePath}",
            archives.isComplete,
        )

        projectRoot = File(context.cacheDir, "kt-project-${System.nanoTime()}")
        val sources = File(projectRoot, "src/main/kotlin").apply { mkdirs() }
        source = File(sources, "Sample.kt")
        source.writeText(
            """
            package sample

            fun onDisk(): String = "unchanged"
            """.trimIndent(),
        )

        dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
            override val compiler = Dispatchers.Default
        }
        service = KotlinLanguageService(
            archives = archives,
            projectRoot = projectRoot,
            dispatchers = dispatchers,
        )
    }

    private fun extract(archive: File, entry: String, target: File) {
        if (target.isFile) return
        java.util.zip.ZipFile(archive).use { zip ->
            val found = zip.getEntry(entry) ?: error("no $entry in ${archive.name}")
            zip.getInputStream(found).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.close()
    }

    @Test
    fun it_handles_kotlin_files_and_nothing_else() {
        assertTrue(service.handles(File("Main.kt")))
        assertTrue(service.handles(File("build.gradle.kts")))
        assertFalse(service.handles(File("Main.java")))
        assertFalse(service.handles(File("README.md")))
    }

    /**
     * A type error, with a line the gutter can underline.
     *
     * The line matters as much as the message: a diagnostic without one is a
     * log entry, and the whole point of `:lsp:api` carrying a position is that
     * the editor can put the cursor on it.
     */
    @Test
    fun a_type_error_comes_back_as_a_placed_diagnostic() = runBlocking {
        val text = """
            package sample

            fun broken() {
                val wrong: String = 1
            }
        """.trimIndent()

        val found = service.diagnostics(source, text)

        assertTrue("no diagnostic for a type error: $found", found.isNotEmpty())
        val error = found.first { it.severity == DiagnosticSeverity.ERROR }
        assertTrue("the message is not the front end's: ${error.message}", "String" in error.message)
        assertEquals("the error is on the declaration's line", 4, error.line)
        assertTrue("the path should be relative to the project", error.hasLocation)
        assertFalse("the path leaked an absolute location", error.file!!.isAbsolute)
    }

    /** Clean code reports nothing, or the gutter is noise on every file. */
    @Test
    fun clean_code_reports_no_diagnostics() = runBlocking {
        val text = """
            package sample

            fun fine(): String = "ok"
        """.trimIndent()

        assertEquals(emptyList<Any>(), service.diagnostics(source, text))
    }

    /**
     * Completion on a receiver declared only in the buffer.
     *
     * Nothing about `local` is on disk, so the answer can only have come from
     * resolving the buffer — and `length` can only have come from resolving it
     * to `kotlin.String`.
     */
    @Test
    fun completion_resolves_a_receiver_from_the_buffer() = runBlocking {
        val text = """
            package sample

            fun edit() {
                val local: String = "x"
                local.
            }
        """.trimIndent()

        val items = service.complete(source, text, text.indexOf("local.") + "local.".length)

        assertTrue("no completions: $items", items.isNotEmpty())
        val length = items.firstOrNull { it.insert == "length" }
        assertNotNull("String.length is missing: ${items.map { it.insert }}", length)
        assertEquals("length is a property, not a method", CompletionKind.FIELD, length!!.kind)
        // `Int`, not `kotlin/Int`: the detail is shown to a person, and
        // KaType.toString() is a debug rendering that reaches the UI. Driving
        // the app is what found that — completion offered
        // `setContentView(android/view/View!)`.
        assertEquals("the detail should read as source, not as descriptors", "Int", length.detail)
    }

    /**
     * The typed prefix filters, and the edit is seen.
     *
     * Two claims at once, because they fail the same way. Changing the type to
     * `Int` and typing `to` must offer `toLong` and not `toByteArray`: the
     * first shows the prefix was applied, the second that the session did not
     * answer from its previous snapshot.
     */
    @Test
    fun completion_filters_by_prefix_and_follows_the_edit() = runBlocking {
        val first = """
            package sample

            fun edit() {
                val local: String = "x"
                local.
            }
        """.trimIndent()
        service.complete(source, first, first.indexOf("local.") + "local.".length)

        val edited = """
            package sample

            fun edit() {
                val local: Int = 1
                local.to
            }
        """.trimIndent()
        val items = service.complete(source, edited, edited.indexOf("local.to") + "local.to".length)

        assertTrue("no completions after an edit: $items", items.isNotEmpty())
        assertTrue(
            "every proposal should match the typed prefix: ${items.map { it.insert }}",
            items.all { it.insert.startsWith("to") },
        )
        assertTrue(
            "Int.toLong is missing, so the edit was not seen: ${items.map { it.insert }}",
            items.any { it.insert == "toLong" },
        )
    }

    /**
     * Functions come back as methods, with their parameters in the label.
     *
     * `label` and `insert` differ for anything carrying a signature — the user
     * reads `subSequence(kotlin/Int, kotlin/Int)` and the buffer gets
     * `subSequence`. That is what `CompletionItem` documents, and a service
     * that put the label in the buffer would produce code that does not parse.
     */
    @Test
    fun a_function_proposal_carries_its_signature_in_the_label_only() = runBlocking {
        val text = """
            package sample

            fun edit() {
                val local: String = "x"
                local.sub
            }
        """.trimIndent()

        val items = service.complete(source, text, text.indexOf("local.sub") + "local.sub".length)

        val subSequence = items.firstOrNull { it.insert == "subSequence" }
        assertNotNull("String.subSequence is missing: ${items.map { it.insert }}", subSequence)
        assertEquals(CompletionKind.METHOD, subSequence!!.kind)
        assertTrue(
            "the label should carry the parameters: ${subSequence.label}",
            subSequence.label == "subSequence(Int, Int)",
        )
    }

    /**
     * A declaration from a **sibling file on disk** resolves from the buffer.
     *
     * Everything else here could pass with a session that reads only the
     * buffer. `onDisk()` is declared in `Sample.kt` and appears nowhere in the
     * text being analysed, so calling it without a diagnostic proves the source
     * root is actually in the session — and completing it proves the same for
     * the path a user takes.
     */
    @Test
    fun a_declaration_from_another_file_in_the_project_resolves() = runBlocking {
        val text = """
            package sample

            fun caller() {
                val greeting: String = onDi
            }
        """.trimIndent()

        val items = service.complete(source, text, text.indexOf("onDi") + "onDi".length)

        assertTrue(
            "onDisk() from Sample.kt is missing: ${items.map { it.insert }}",
            items.any { it.insert == "onDisk" },
        )
        val onDisk = items.first { it.insert == "onDisk" }
        assertEquals("its return type should be resolved", "String", onDisk.detail)
    }

    /**
     * Calling it is not an error, which is the other half of the same claim.
     *
     * Completion could offer a name the front end would still reject. This
     * asserts the call itself type-checks against the sibling file's signature.
     */
    @Test
    fun calling_a_sibling_files_function_produces_no_diagnostic() = runBlocking {
        val text = """
            package sample

            fun caller() {
                val greeting: String = onDisk()
            }
        """.trimIndent()

        assertEquals(emptyList<Any>(), service.diagnostics(source, text))
    }

    /**
     * **Extensions.** `uppercase` is not a member of `String` and must appear.
     *
     * This is the one that was a wall. `KaType.scope` gives eight members and
     * `uppercase` is none of them — it is a top-level callable in
     * `kotlin.text`, living in a binary jar the Analysis API will resolve by
     * name and refuses to enumerate. The names come from an index built at
     * build time; see `tools/analysisapi/FINDINGS.md` section 20.
     *
     * Asserted on a receiver whose type is declared only in the buffer, so a
     * pass still means the front end resolved `String` and then judged
     * `uppercase` applicable to it — not that a name was matched in a list.
     */
    @Test
    fun completion_offers_extensions_from_the_standard_library() = runBlocking {
        val text = """
            package sample

            fun edit() {
                val local: String = "x"
                local.upp
            }
        """.trimIndent()

        val items = service.complete(source, text, text.indexOf("local.upp") + "local.upp".length)

        assertTrue(
            "String.uppercase is missing, so extensions are still not reaching completion: " +
                items.map { it.insert },
            items.any { it.insert == "uppercase" },
        )
        assertEquals(CompletionKind.METHOD, items.first { it.insert == "uppercase" }.kind)
    }

    /**
     * An extension that does **not** apply to the receiver stays out.
     *
     * The index knows `getOrPut` and the prefix matches, so a service that
     * offered every indexed name would include it. It is a `MutableMap`
     * extension, and only the applicability check keeps it out — which is the
     * difference between completion and a word list.
     *
     * `getOrPut` specifically because the obvious choice was wrong.
     * `mapNotNull` looked like an `Iterable`-only extension and is not:
     * `kotlin.text` defines it on `CharSequence`, so it applies to a `String`
     * and the checker was right to offer it. Anything picked for this test has
     * to be checked against the index rather than against intuition —
     * `kotlin.text` shadows a great deal of `kotlin.collections`.
     */
    @Test
    fun an_extension_that_does_not_apply_to_the_receiver_is_not_offered() = runBlocking {
        val text = """
            package sample

            fun edit() {
                val local: String = "x"
                local.getOrPu
            }
        """.trimIndent()

        val items =
            service.complete(source, text, text.indexOf("local.getOrPu") + "local.getOrPu".length)

        assertTrue(
            "getOrPut is a MutableMap extension and must not be offered on a String: " +
                items.map { it.insert },
            items.none { it.insert == "getOrPut" },
        )
    }

    /**
     * What a completion with extensions costs, warm.
     *
     * The point of resolving the index by prefix *before* touching a symbol is
     * that cost tracks the request. The rejected shape — enumerate what is
     * visible, then ask which applies — measured four times the latency for an
     * answer that did not change (`tools/analysisapi/FINDINGS.md` section 16),
     * so this is the number that says the redesign was worth it.
     *
     * Only the shape is asserted. A millisecond threshold would fail on other
     * hardware for a reason nobody could act on; the measurement goes to
     * logcat and to FINDINGS.
     */
    @Test
    fun a_completion_with_extensions_stays_cheap() = runBlocking {
        val text = """
            package sample

            fun edit() {
                val local: String = "x"
                local.upp
            }
        """.trimIndent()
        val offset = text.indexOf("local.upp") + "local.upp".length

        val cold = measure { service.complete(source, text, offset) }
        val warm = (1..4).map { measure { service.complete(source, text, offset) } }.sorted()
        val median = warm[warm.size / 2]
        Log.i(TAG, "completion with extensions: cold=${cold}ms warm=${median}ms of $warm")

        assertTrue(
            "a warm completion (${median}ms) was no cheaper than the first (${cold}ms)",
            median < cold,
        )
    }

    private suspend fun measure(body: suspend () -> Unit): Long {
        val started = System.nanoTime()
        body()
        return (System.nanoTime() - started) / 1_000_000
    }

    /**
     * A malformed buffer is an ordinary answer, not a crash.
     *
     * `LanguageService` says every method may return nothing and that nothing
     * is ordinary — these are asked on keystrokes, against text that is
     * mid-edit and usually does not parse. A service that threw here would turn
     * typing into a stream of errors.
     */
    @Test
    fun an_unparseable_buffer_answers_rather_than_throws() = runBlocking<Unit> {
        val text = "package sample\n\nfun broken( { val ="

        // Not asserting *what* comes back — half a declaration has no right
        // answer. Asserting only that asking is safe.
        service.diagnostics(source, text)
        service.complete(source, text, text.length)
        service.signatureAt(source, text, text.length)
    }

    private companion object {
        const val TAG = "KotlinLanguageServiceTest"
    }
}
