package com.osamu.aide.lsp.nativelsp

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.lsp.api.CompletionKind
import com.osamu.aide.toolchain.nativetools.ClangToolchain
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
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
import java.util.concurrent.TimeUnit

/**
 * C++ intelligence, end to end, against a real clangd.
 *
 * Everything here goes through [com.osamu.aide.lsp.api.LanguageService], the
 * same interface the Java service implements, because that is what the editor
 * holds -- a test that reached past it could pass while the editor got nothing.
 *
 * There is no useful fake. The questions are whether clangd starts under the
 * dynamic linker, whether it finds the standard library from a
 * `compile_flags.txt` instead of by executing the compiler, and whether its
 * answers survive the trip back through this client; a stub server would
 * answer none of them.
 */
@RunWith(AndroidJUnit4::class)
class ClangdServiceTest {

    private lateinit var context: Context
    private lateinit var project: File
    private lateinit var service: ClangdService

    private val source = """
        #include <string>

        struct Greeter {
            std::string greeting;
            int count;
            int repeat(int times);
        };

        int Greeter::repeat(int times) {
            return times * count;
        }

        int main() {
            Greeter g;
            return g.repeat(2);
        }
    """.trimIndent()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "toolchain/usr")
        stageIfNeeded(root.parentFile!!)
        assumeTrue(
            "no C/C++ toolchain staged; push toolchain.tar to " +
                "${context.getExternalFilesDir(null)} -- see tools/clang/FINDINGS.md",
            File(root, "bin/clangd").exists(),
        )

        project = File(context.filesDir, "clangd-project").apply { deleteRecursively(); mkdirs() }
        val clang = ClangToolchain(
            root = root,
            abi = Build.SUPPORTED_ABIS.first(),
            launch = LinkerLaunch.forThisProcess(),
            runner = NativeToolRunner(NativeToolchain.from(context), DefaultDispatcherProvider()),
        )
        service = ClangdService(clang, project, DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.close()
    }

    private fun stageIfNeeded(destination: File) {
        if (File(destination, "usr/bin/clangd").exists()) return
        val archive = File(context.getExternalFilesDir(null), "toolchain.tar")
        if (!archive.isFile) return
        destination.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", destination.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun file(name: String = "main.cpp", text: String = source): File =
        File(project, name).apply { writeText(text) }

    @Test
    fun it_claims_c_and_cpp_and_leaves_java_alone() {
        assertTrue(service.handles(File("a.cpp")))
        assertTrue(service.handles(File("a.c")))
        assertTrue(service.handles(File("a.h")))
        // The editor asks whichever service claims a file. Claiming Java would
        // hand it clangd's opinion of a Java file, which is not an error --
        // just wrong.
        assertFalse(service.handles(File("Main.java")))
        assertFalse(service.handles(File("build.gradle.kts")))
    }

    /**
     * The load-bearing one. A diagnostic at the right line means clangd found
     * `<string>` and parsed -- which it can only do if `compile_flags.txt`
     * replaced the `--query-driver` it is not allowed to use.
     */
    @Test
    fun it_reports_a_real_diagnostic() = runBlocking {
        val broken = source.replace("return times * count;", "return times * missing_name;")
        val target = file(text = broken)

        val diagnostics = service.diagnostics(target, broken)

        assertTrue("clangd reported nothing for code that does not compile", diagnostics.isNotEmpty())
        val error = diagnostics.first { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(
            "unexpected message: ${error.message}",
            error.message.contains("missing_name"),
        )
        assertEquals(
            "the diagnostic is on the wrong line",
            broken.lineSequence().indexOfFirst { it.contains("missing_name") } + 1,
            error.line,
        )
    }

    /** Clean code produces no errors -- the other half of the same claim. */
    @Test
    fun it_reports_nothing_for_code_that_compiles() = runBlocking {
        val target = file()

        val diagnostics = service.diagnostics(target, source)

        assertTrue(
            "clangd invented errors in valid code: ${diagnostics.map { it.message }}",
            diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
        )
    }

    /**
     * Completion after `g.` must know the type of `g`, which needs the file
     * parsed rather than scanned for words.
     */
    @Test
    fun it_completes_members_of_a_type_it_had_to_parse() = runBlocking {
        val withPrefix = source.replace("return g.repeat(2);", "return g.re;")
        val target = file(text = withPrefix)
        val offset = withPrefix.indexOf("g.re") + "g.re".length

        val items = service.complete(target, withPrefix, offset)

        assertTrue("no proposals at all", items.isNotEmpty())
        val repeat = items.firstOrNull { it.label.startsWith("repeat") }
        assertNotNull("`repeat` was not proposed: ${items.take(10).map { it.label }}", repeat)
        // What goes in the buffer is the name; the signature is for reading.
        assertEquals("repeat", repeat!!.insert)
        assertEquals(
            "a member function should be a METHOD; clangd sent ${repeat.kind} for ${repeat.label}",
            CompletionKind.METHOD,
            repeat.kind,
        )
    }

    @Test
    fun it_finds_where_something_is_declared() = runBlocking {
        val target = file()
        val offset = source.indexOf("g.repeat(2)") + "g.".length

        val location = service.definition(target, source, offset)

        assertNotNull("no definition found for repeat", location)
        assertEquals("main.cpp", location!!.file.path)
        // 1-based, like Diagnostic and the editor's jumpTo.
        assertEquals(
            source.lineSequence().indexOfFirst { it.contains("int Greeter::repeat") } + 1,
            location.line,
        )
    }

    @Test
    fun it_gives_a_signature_for_the_thing_under_the_cursor() = runBlocking {
        val target = file()
        val offset = source.indexOf("g.repeat(2)") + "g.".length

        val signature = service.signatureAt(target, source, offset)

        assertNotNull("no signature", signature)
        assertTrue("unexpected signature: $signature", signature!!.contains("repeat"))
        assertTrue("the signature does not name its parameter type: $signature", signature.contains("int"))
    }

    /**
     * Nothing is left running. clangd holds a background index and a share of
     * a 139 MB library; a server per closed project would accumulate.
     */
    @Test
    fun closing_stops_the_server() = runBlocking {
        val target = file()
        service.diagnostics(target, source)

        service.close()

        // Answers become empty rather than throwing: a closed service is a
        // state the editor can reach by racing a project switch.
        assertTrue(service.complete(target, source, 10).isEmpty())
    }
}
