package com.osamu.aide.lsp.node

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import com.osamu.aide.toolchain.nativetools.NodeToolchain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [NodeLanguageService] against a real Node on a device.
 *
 * [NodeSyntaxCheckTest] covers the parsing against captured output; this covers
 * everything that fixture cannot: that node starts at all through the linker
 * from a language service, that a buffer which was never saved is what gets
 * checked, and that the output shape those fixtures were captured from is still
 * the shape this Node produces.
 */
@RunWith(AndroidJUnit4::class)
class NodeLanguageServiceTest {

    private lateinit var context: Context
    private lateinit var service: NodeLanguageService
    private lateinit var project: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "node")
        install(root)
        assumeTrue(
            "no Node staged: tools/node/fetch-node.sh, then push node.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(root, "bin/node").isFile,
        )

        service = NodeLanguageService(
            node = NodeToolchain(root, LinkerLaunch.forThisProcess()),
            dispatchers = DefaultDispatcherProvider(),
            scratch = File(context.cacheDir, "js-check").apply { mkdirs() },
        )
        project = File(context.filesDir, "js-project").apply { deleteRecursively(); mkdirs() }
    }

    private fun install(root: File) {
        if (File(root, "bin/node").isFile) return
        val archive = File(context.getExternalFilesDir(null), "node.tar")
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private val entry: File get() = File(project, "index.js")

    @Test
    fun it_claims_javascript_and_nothing_else() {
        assertTrue(service.handles(File("index.js")))
        assertTrue(service.handles(File("server.mjs")))
        assertTrue(service.handles(File("legacy.cjs")))
        assertTrue("it claimed a Java file", !service.handles(File("Main.java")))
        // .jsx is not claimed: the editor has no grammar for it either.
        assertTrue("it claimed JSX", !service.handles(File("App.jsx")))
    }

    @Test
    fun a_syntax_error_is_reported_where_it_is() = runBlocking {
        val broken = "const a = 1;\nfunction f( {\n  return a;\n}\n"

        val diagnostics = service.diagnostics(entry, broken)
        Log.i(TAG, "broken -> $diagnostics")

        assertEquals("expected exactly one diagnostic", 1, diagnostics.size)
        val only = diagnostics.single()
        assertEquals(DiagnosticSeverity.ERROR, only.severity)
        assertEquals(entry, only.file)
        assertEquals(3, only.line)
        assertTrue("no position: $only", only.column > 0)
        assertTrue("the message says nothing: $only", only.message.startsWith("SyntaxError"))
    }

    /**
     * The buffer is checked, not the file on disk.
     *
     * The whole point of a language service is answering about text that has
     * not been saved. A service that checked the file would report the last
     * save's errors while the user looked at the current ones.
     */
    @Test
    fun it_checks_the_buffer_and_not_what_is_on_disk() = runBlocking {
        entry.writeText("const fine = 1;\n")

        val diagnostics = service.diagnostics(entry, "const = ;\n")
        Log.i(TAG, "unsaved -> $diagnostics")

        assertEquals("the file on disk was checked instead", 1, diagnostics.size)
        assertEquals(1, diagnostics.single().line)
        // And the reverse: a clean buffer over a broken file is clean.
        entry.writeText("function f( {\n")
        assertEquals(emptyList<Any>(), service.diagnostics(entry, "const fine = 1;\n"))
    }

    /** Redeclaration is an early error, so this service catches it. */
    @Test
    fun it_reports_a_redeclaration() = runBlocking {
        val diagnostics = service.diagnostics(entry, "const a = 1;\nlet a = 2;\n")
        Log.i(TAG, "redeclared -> $diagnostics")

        assertEquals(1, diagnostics.size)
        assertEquals(2, diagnostics.single().line)
        assertTrue(
            "not the redeclaration: ${diagnostics.single().message}",
            diagnostics.single().message.contains("already been declared"),
        )
    }

    /**
     * A name that does not exist is **not** reported, and that is the contract.
     *
     * `--check` parses; it does not resolve. Asserting the silence here is what
     * stops someone later reading the service as a type checker and filing the
     * absence as a bug -- and what would notice if it were ever replaced by
     * something that does resolve.
     */
    @Test
    fun it_says_nothing_about_a_name_that_does_not_exist() = runBlocking {
        val diagnostics = service.diagnostics(entry, "console.log(notDefinedAnywhere);\n")
        Log.i(TAG, "undefined name -> $diagnostics")

        assertEquals(emptyList<Any>(), diagnostics)
    }

    @Test
    fun a_file_that_is_fine_produces_nothing() = runBlocking {
        val clean = "const greeting = 'hello';\nconsole.log(greeting);\n"
        assertEquals(emptyList<Any>(), service.diagnostics(entry, clean))
    }

    /** The three it does not implement answer nothing rather than throwing. */
    @Test
    fun the_other_three_questions_are_answered_with_silence() = runBlocking {
        val text = "const greeting = 'hello';\n"

        assertEquals(emptyList<Any>(), service.complete(entry, text, offset = 6))
        assertEquals(null, service.definition(entry, text, offset = 6))
        assertEquals(null, service.signatureAt(entry, text, offset = 6))
    }

    private companion object {
        const val TAG = "NodeLanguageService"
    }
}
