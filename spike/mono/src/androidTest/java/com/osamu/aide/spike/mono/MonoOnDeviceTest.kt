package com.osamu.aide.spike.mono

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mono, started from app-private storage.
 *
 * M10's C# half. See the module's `build.gradle.kts` for why this is mono and
 * not the .NET SDK, and for the two things about the archive that shape every
 * test here.
 *
 * **`adb shell run-as` cannot answer any of this**, for the reason
 * `tools/clang/FINDINGS.md` §7 gives: it runs in a domain that *is* allowed to
 * execute from app storage, and will contradict every assertion below.
 */
@RunWith(AndroidJUnit4::class)
class MonoOnDeviceTest {

    private lateinit var context: Context
    private lateinit var prefix: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.getExternalFilesDir(null), ARCHIVE)
        assumeTrue(
            "no mono staged: build one with tools/mono/fetch-mono.sh and push " +
                "$ARCHIVE to ${context.getExternalFilesDir(null)}",
            archive.isFile,
        )

        prefix = File(context.filesDir, "mono")
        if (!File(prefix, "bin/mono-sgen").canRead()) {
            prefix.mkdirs()
            // Unpacked by a child of this process: a file written by the shell
            // carries the shell's SELinux label and the app cannot use it.
            // Also the only way the symlinks survive at all.
            ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", prefix.absolutePath)
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
        }
        assumeTrue("the archive unpacked without a runtime", File(prefix, "bin/mono-sgen").canRead())
    }

    /**
     * Mono's own config, with its build-time library directory rewritten.
     *
     * **`System.IO` P/Invokes `System.Native`, and the shipped config maps it
     * to `${'$'}mono_libdir/libmono-native.so`** -- a variable mono expands to the
     * libdir fixed when Termux built the package,
     * `/data/data/com.termux/files/usr/lib`, which this app neither has nor can
     * create. `Interop.Sys`'s static constructor therefore throws
     * `DllNotFoundException`, every managed file operation fails, and `mcs`
     * reports each one as `CS2001 ... could not be found` -- a diagnostic
     * pointing at the user's source file for a cause that is a missing shared
     * library.
     *
     * The first attempt *appended* a dllmap for the absolute path and changed
     * nothing, which read as "MONO_CONFIG is ignored". It was being read all
     * along: with no config the failing name is `System.Native`, and with that
     * one it became the Termux path -- the appended entry lost to the shipped
     * one above it. Rewriting the variable fixes every entry that uses it at
     * once, which is three.
     *
     * `LD_LIBRARY_PATH` cannot do this job: what is resolved is a path, not a
     * soname.
     */
    private fun relocatedConfig(): File {
        val generated = File(context.filesDir, "mono-config-relocated")
        val libdir = File(prefix, "lib").absolutePath
        if (!generated.isFile) {
            val original = File(prefix, "etc/mono/config").takeIf { it.isFile }?.readText()
                ?: "<configuration>\n</configuration>"
            generated.writeText(original.replace(MONO_LIBDIR, libdir))
        }
        return generated
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    /**
     * Runs `mono-sgen`, not `bin/mono`, through the linker.
     *
     * `bin/mono` is a symlink and the linker wants the file itself.
     * **`MONO_PATH` is the whole question**: mono resolves its class library
     * against a prefix fixed when Termux built it, and this app cannot create
     * that path, so the assemblies have to be pointed at explicitly or nothing
     * loads.
     */
    private fun mono(
        vararg arguments: String,
        timeoutSeconds: Long = 180,
        workingDir: File = context.filesDir,
    ): Run {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(
            listOf(LINKER, File(prefix, "bin/mono-sgen").absolutePath) + arguments,
        ).redirectErrorStream(true)
        builder.directory(workingDir)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
            put("MONO_PATH", File(prefix, "lib/mono/4.5").absolutePath)
            put("MONO_CONFIG", relocatedConfig().absolutePath)
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val process = builder.start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Run(-1, "timed out after ${timeoutSeconds}s", System.currentTimeMillis() - started)
        }
        return Run(process.exitValue(), text, System.currentTimeMillis() - started)
    }

    /** What the archive actually contains, since two entries are not what they look like. */
    @Test
    fun the_runtime_is_a_binary_and_the_compiler_is_an_assembly() {
        assertTrue("no mono-sgen", File(prefix, "bin/mono-sgen").canRead())
        assertTrue("no mcs.exe", File(prefix, "lib/mono/4.5/mcs.exe").canRead())

        // bin/mcs is a shell script pointing at a prefix that does not exist
        // here, which is why nothing invokes it.
        val wrapper = File(prefix, "bin/mcs")
        if (wrapper.isFile) {
            val text = wrapper.readText()
            Log.i(TAG, "bin/mcs is: ${text.lines().firstOrNull()}")
            assertTrue(
                "bin/mcs no longer hardcodes Termux's prefix, so it may be usable directly now",
                text.contains("/data/data/com.termux/"),
            )
        }
    }

    /** Question 1: does the runtime start at all? */
    @Test
    fun mono_starts_and_reports_its_version() {
        val run = mono("--version")
        Log.i(TAG, "mono --version -> exit=${run.exit} in ${run.millis} ms: ${run.output.take(200)}")

        assertEquals("mono did not start: ${run.output}", 0, run.exit)
        assertTrue("no version in '${run.output}'", run.output.contains("Mono JIT compiler version"))
    }

    /** Question 2: does it run an assembly -- here, the compiler itself? */
    @Test
    fun the_compiler_assembly_runs() {
        val run = mono(File(prefix, "lib/mono/4.5/mcs.exe").absolutePath, "--version")
        Log.i(TAG, "mcs --version -> exit=${run.exit}: ${run.output.take(200)}")

        assertEquals("mcs did not run: ${run.output}", 0, run.exit)
        assertTrue("no compiler version in '${run.output}'", run.output.contains("Mono C# compiler"))
    }

    /**
     * Question 3: **M10's acceptance test for the C# half** -- a console app
     * compiles, and then runs.
     *
     * This failed for most of the spike, and the diagnostic was the reason. Every
     * file handed to `mcs` came back as `CS2001 ... could not be found`,
     * including `mcs.exe` itself, which the runtime had just loaded from the
     * same directory. The cause was not the compiler and not the path: managed
     * IO was unbound, because `System.Native` maps to
     * `${'$'}mono_libdir/libmono-native.so` and mono expands that variable to the
     * libdir Termux built with. One substitution in the config
     * ([relocatedConfig]) fixes it, and the compiler is fine.
     */
    @Test
    fun a_console_app_compiles_and_runs() {
        val project = File(context.filesDir, "cs-project").apply { deleteRecursively(); mkdirs() }
        val source = File(project, "Hello.cs")
        source.writeText(
            """
            using System;

            class Hello {
                static void Main() {
                    Console.WriteLine("hello from mono " + (6 * 7));
                }
            }
            """.trimIndent(),
        )
        val assembly = File(project, "Hello.exe")

        val mcs = File(prefix, "lib/mono/4.5/mcs.exe").absolutePath
        val compile = mono(mcs, source.absolutePath, "-out:${assembly.absolutePath}")
        Log.i(TAG, "compile -> exit=${compile.exit} in ${compile.millis} ms: ${compile.output.take(300)}")
        assertEquals("compilation failed: ${compile.output}", 0, compile.exit)
        assertTrue("no assembly was written", assembly.isFile)

        val execute = mono(assembly.absolutePath)
        Log.i(TAG, "run -> exit=${execute.exit} in ${execute.millis} ms: ${execute.output}")
        assertEquals("the assembly did not run: ${execute.output}", 0, execute.exit)
        assertTrue("wrong output: '${execute.output}'", execute.output.contains("hello from mono 42"))
    }

    /**
     * And the compiler is genuinely compiling, not passing anything through.
     *
     * A broken source has to fail, or the test above would pass for a toolchain
     * that writes an empty assembly and never looks at the input.
     */
    @Test
    fun a_broken_source_is_rejected_with_the_compilers_own_diagnostic() {
        val project = File(context.filesDir, "cs-broken").apply { deleteRecursively(); mkdirs() }
        val source = File(project, "Broken.cs").apply { writeText("class Broken { this is not C# }") }

        val compile = mono(
            File(prefix, "lib/mono/4.5/mcs.exe").absolutePath,
            source.absolutePath,
            "-out:${File(project, "Broken.exe").absolutePath}",
        )
        Log.i(TAG, "broken source -> exit=${compile.exit}: ${compile.output.take(200)}")

        assertEquals("a broken source compiled", 1, compile.exit)
        assertTrue(
            "no compiler diagnostic, so the failure says nothing: ${compile.output}",
            compile.output.contains("error CS"),
        )
        assertTrue(
            "it failed as a missing file again, so managed IO has regressed: ${compile.output}",
            !compile.output.contains("CS2001"),
        )
    }

    /**
     * What managed IO actually says, from the REPL rather than the compiler.
     *
     * `mcs` reports every unreadable file as `CS2001 ... could not be found`,
     * which conflates "absent" with "could not be opened". `csharp.exe` is an
     * assembly like `mcs.exe`, so it runs the same way, and it will report the
     * exception rather than translating it into a compiler diagnostic.
     */
    @Test
    fun what_managed_io_says_about_a_file_that_exists() {
        val repl = File(prefix, "lib/mono/4.5/csharp.exe")
        assumeTrue("no csharp.exe in this archive", repl.isFile)

        val probe = File(context.filesDir, "probe.txt").apply { writeText("readable") }
        val script = File(context.filesDir, "probe.csx")
        script.writeText(
            """
            var p = "${probe.absolutePath}";
            System.Console.WriteLine("Exists=" + System.IO.File.Exists(p));
            try { System.Console.WriteLine("Read=" + System.IO.File.ReadAllText(p)); }
            catch (System.Exception e) { System.Console.WriteLine("Threw=" + e.GetType().FullName + ": " + e.Message); }
            System.Console.WriteLine("Cwd=" + System.IO.Directory.GetCurrentDirectory());
            """.trimIndent(),
        )

        val run = mono(repl.absolutePath, script.absolutePath)
        Log.i(TAG, "managed io -> exit=${run.exit}: ${run.output.take(400)}")

        assertTrue(
            "the REPL produced nothing, so this says nothing: ${run.output}",
            run.output.isNotBlank(),
        )
    }

    /**
     * Is `MONO_CONFIG` read at all? Asked through the REPL, not `--version`.
     *
     * The first attempt put malformed XML there and ran `mono --version`, which
     * started and printed normally -- suggestive, and not proof, because
     * `--version` may return before the config is ever parsed. `csharp.exe`
     * runs managed code and therefore reaches the P/Invoke that fails, so if
     * the file is read at all, a broken one has to change something.
     */
    @Test
    fun whether_mono_config_is_honoured_is_recorded() {
        val repl = File(prefix, "lib/mono/4.5/csharp.exe")
        assumeTrue("no csharp.exe in this archive", repl.isFile)
        val script = File(context.filesDir, "noop.csx").apply {
            writeText("System.Console.WriteLine(\"ran\");")
        }

        fun withConfig(config: File?): String {
            val builder = ProcessBuilder(
                listOf(LINKER, File(prefix, "bin/mono-sgen").absolutePath,
                    repl.absolutePath, script.absolutePath),
            ).redirectErrorStream(true)
            builder.directory(context.filesDir)
            builder.environment().apply {
                put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
                // Without this the BCL is not found and all three arms fail
                // identically for a reason that has nothing to do with config.
                put("MONO_PATH", File(prefix, "lib/mono/4.5").absolutePath)
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                if (config != null) put("MONO_CONFIG", config.absolutePath)
            }
            val process = builder.start()
            val text = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(120, TimeUnit.SECONDS)
            return text
        }

        val broken = File(context.filesDir, "mono-config-broken").apply {
            writeText("<configuration><dllmap dll=\"x\"")
        }
        val withBroken = withConfig(broken)
        val withNone = withConfig(null)
        val withMapped = withConfig(relocatedConfig())

        Log.i(TAG, "config[broken]  -> ${withBroken.take(160)}")
        Log.i(TAG, "config[absent]  -> ${withNone.take(160)}")
        Log.i(TAG, "config[dllmap]  -> ${withMapped.take(160)}")

        assertTrue("the REPL said nothing under any config", withNone.isNotBlank())
    }

    private companion object {
        const val TAG = "MonoSpike"
        const val ARCHIVE = "mono.tar"
        const val LINKER = "/system/bin/linker64"

        /** The variable mono expands to its build-time libdir. */
        const val MONO_LIBDIR = "${'$'}mono_libdir"
    }
}
