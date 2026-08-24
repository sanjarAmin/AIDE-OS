package com.osamu.aide.toolchain.nativetools

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the fast build path's one native dependency actually works on a real
 * Android runtime: that a bundled executable can be run from the native library
 * directory under Android's W^X policy, and that it compiles real resources.
 *
 * Everything else in the pipeline (ECJ, D8, apksig) is pure JVM, so this is the
 * only part that can fail for platform reasons rather than logic reasons.
 */
@RunWith(AndroidJUnit4::class)
class Aapt2InstrumentedTest {

    private lateinit var toolchain: NativeToolchain
    private lateinit var runner: NativeToolRunner
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        toolchain = NativeToolchain.from(context)
        runner = NativeToolRunner(toolchain, DefaultDispatcherProvider())
        workDir = File(context.cacheDir, "aapt2-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun aapt2_is_packaged_and_executable() {
        assumeTrue(Build.VERSION.SDK_INT >= NativeTool.AAPT2.minApiLevel)

        when (val availability = toolchain.locate(NativeTool.AAPT2)) {
            is ToolAvailability.Available -> {
                assertTrue("not executable", availability.executable.canExecute())
                assertTrue("empty binary", availability.executable.length() > 0)
            }
            else -> throw AssertionError("aapt2 unavailable: $availability")
        }
    }

    @Test
    fun api_gate_matches_this_device() {
        val availability = toolchain.locate(NativeTool.AAPT2)
        if (Build.VERSION.SDK_INT < NativeTool.AAPT2.minApiLevel) {
            assertTrue(
                "expected the API gate to reject this device",
                availability is ToolAvailability.UnsupportedApiLevel,
            )
        } else {
            assertTrue(
                "expected aapt2 to be available, got $availability",
                availability is ToolAvailability.Available,
            )
        }
    }

    /** Exec works at all: the binary loads and runs under the platform linker. */
    @Test
    fun aapt2_reports_its_version() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= NativeTool.AAPT2.minApiLevel)

        val output = Output()
        val result = runner.run(NativeTool.AAPT2, listOf("version"), onLine = output.sink)
        val value = (result as? AppResult.Success)?.value
            ?: throw AssertionError("run failed: $result")

        assertEquals("stderr: ${output.stderr}", 0, value.exitCode)
        // aapt2 routes everything through its own diagnostics printer, which
        // writes to stderr -- including `version`, and including success output.
        // Reading stdout here would silently pass on a broken binary.
        assertTrue(
            "unexpected version output: ${output.diagnostics}",
            output.diagnostics.contains("Android Asset Packaging Tool"),
        )
        assertTrue("expected nothing on stdout, got ${output.stdout}", output.stdout.isBlank())
    }

    /** The real thing: compile a resource file into aapt2's binary format. */
    @Test
    fun aapt2_compiles_a_string_resource() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= NativeTool.AAPT2.minApiLevel)

        val values = File(workDir, "res/values").apply { mkdirs() }
        File(values, "strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">AIDE-OS</string>
                <string name="build_ok">Compiled on device</string>
            </resources>
            """.trimIndent(),
        )
        val outDir = File(workDir, "compiled").apply { mkdirs() }

        val output = Output()
        val result = runner.run(
            tool = NativeTool.AAPT2,
            args = listOf("compile", File(values, "strings.xml").absolutePath, "-o", outDir.absolutePath),
            onLine = output.sink,
        )
        val value = (result as? AppResult.Success)?.value
            ?: throw AssertionError("run failed: $result")

        assertEquals("aapt2 said: ${output.diagnostics}", 0, value.exitCode)

        val produced = outDir.listFiles().orEmpty()
        assertEquals(
            "expected exactly one compiled resource, got ${produced.map { it.name }}",
            1,
            produced.size,
        )
        assertTrue(
            "expected a .flat file, got ${produced.first().name}",
            produced.first().name.endsWith(".flat"),
        )
        assertTrue("compiled resource is empty", produced.first().length() > 0)
    }

    /** A broken resource must surface as a diagnostic, not a silent success. */
    @Test
    fun aapt2_reports_malformed_resources() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= NativeTool.AAPT2.minApiLevel)

        val values = File(workDir, "bad/values").apply { mkdirs() }
        File(values, "strings.xml").writeText("<resources><string name=\"x\">unclosed</resources>")
        val outDir = File(workDir, "bad-out").apply { mkdirs() }

        val output = Output()
        val result = runner.run(
            tool = NativeTool.AAPT2,
            args = listOf("compile", File(values, "strings.xml").absolutePath, "-o", outDir.absolutePath),
            onLine = output.sink,
        )
        val value = (result as? AppResult.Success)?.value
            ?: throw AssertionError("run failed: $result")

        assertTrue("malformed XML should fail", value.exitCode != 0)
        assertTrue("expected a diagnostic message", output.diagnostics.isNotBlank())
    }

    /**
     * Puts a run back together the way it used to arrive whole.
     *
     * The runner streams now, so a test that wants to assert on all of stdout or
     * all of stderr has to collect it. Keeping the two apart is the point of two
     * of these tests: aapt2 says everything on stderr, and a version check that
     * read stdout would pass on a binary that printed nothing.
     */
    private class Output {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val sink: (ToolLine) -> Unit = { line ->
            when (line.stream) {
                ToolStream.STDOUT -> stdout
                ToolStream.STDERR -> stderr
            }.appendLine(line.text)
        }

        /** What the engine would show the user: stderr, or stdout if it is silent. */
        val diagnostics: String
            get() = stderr.toString().trim().ifBlank { stdout.toString().trim() }
    }
}
