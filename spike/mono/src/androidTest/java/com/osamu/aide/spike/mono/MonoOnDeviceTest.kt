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
     * Mono's config, with the one `dllmap` that makes managed IO work.
     *
     * **`System.IO` P/Invokes a library by an absolute path fixed at build
     * time.** `Interop.Sys` binds
     * `/data/data/com.termux/files/usr/lib/../lib/libmono-native.so`, a prefix
     * this app cannot create, so its type initializer throws
     * `DllNotFoundException` and *every* file operation fails. `mcs` reports
     * that as `CS2001 ... could not be found`, which reads as a missing source
     * file and sent this spike looking at paths for an hour.
     *
     * `LD_LIBRARY_PATH` cannot help: the name being resolved is a path, not a
     * soname. `dllmap` is mono's own mechanism for exactly this, and the
     * library itself is in the archive at `lib/libmono-native.so`.
     */
    private fun relocatedConfig(): File {
        val generated = File(context.filesDir, "mono-config-relocated")
        if (generated.isFile) return generated
        val original = File(prefix, "etc/mono/config").takeIf { it.isFile }?.readText()
            ?: "<configuration>\n</configuration>"
        val native = File(prefix, "lib/libmono-native.so").absolutePath
        generated.writeText(
            original.replaceFirst(
                "<configuration>",
                "<configuration>\n" +
                    """	<dllmap dll="$TERMUX_NATIVE" target="$native" />""" + "\n" +
                    """	<dllmap dll="libmono-native" target="$native" />""",
            ),
        )
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
     * Question 3: the milestone's acceptance test, and **it does not pass**.
     *
     * A C# console app does not compile here, and the reason is not the
     * compiler. Every file handed to `mcs` comes back as
     *
     * ```
     * error CS2001: Source file `...Hello.cs' could not be found
     * ```
     *
     * including `mcs.exe` itself, which the runtime had just loaded from the
     * same directory. Managed IO cannot open **anything**:
     * [what_managed_io_says_about_a_file_that_exists] gets the real error out of
     * the REPL, and it is a `DllNotFoundException` for
     * `/data/data/com.termux/files/usr/lib/../lib/libmono-native.so` -- a path
     * baked into `Interop.Sys` when Termux built the package, and one this app
     * cannot create. The type initializer throws, so every `System.IO` call
     * fails, and `mcs` translates that into a missing-file diagnostic.
     *
     * Written as an assertion of the **current** behaviour, the way
     * `:spike:rootfs` asserts that a musl binary cannot be loaded: it is a
     * property of the platform and the package, not a defect in this code, and
     * a test that fails the suite would say the wrong thing. **Invert it when
     * the route opens**, and see `tools/mono/FINDINGS.md` for what would have to
     * change.
     */
    @Test
    fun a_console_app_does_not_compile_because_managed_io_is_unbound() {
        val project = File(context.filesDir, "cs-project").apply { deleteRecursively(); mkdirs() }
        val source = File(project, "Hello.cs")
        source.writeText(
            """
            using System;

            class Hello {
                static void Main() {
                    Console.WriteLine("hello " + (6 * 7));
                }
            }
            """.trimIndent(),
        )
        assertTrue("the fixture was not written", source.isFile)

        val mcs = File(prefix, "lib/mono/4.5/mcs.exe").absolutePath
        val compile = mono(mcs, source.absolutePath, "-out:${File(project, "Hello.exe").absolutePath}")
        Log.i(TAG, "compile -> exit=${compile.exit} in ${compile.millis} ms: ${compile.output.take(300)}")

        assertEquals("mcs unexpectedly succeeded -- invert this test", 1, compile.exit)
        assertTrue(
            "mcs failed for a different reason than the one this records: ${compile.output}",
            compile.output.contains("CS2001") && compile.output.contains("could not be found"),
        )

        // And the same for a file the runtime demonstrably opened, which is what
        // rules out "the source file is genuinely missing".
        val known = mono(mcs, mcs, "-out:${File(project, "x.exe").absolutePath}")
        assertTrue(
            "mcs could read mcs.exe, so managed IO is no longer the cause: ${known.output}",
            known.output.contains("CS2001"),
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
     * Is `MONO_CONFIG` read at all? A deliberately broken file answers it.
     *
     * The `dllmap` above did not take, and there are two very different reasons
     * it might not: mono is not reading the file, or it is reading it and
     * declining to remap an absolute path. Malformed XML distinguishes them --
     * a runtime that parses the file will complain about it.
     */
    @Test
    fun whether_mono_config_is_honoured_is_recorded() {
        val broken = File(context.filesDir, "mono-config-broken").apply {
            writeText("<configuration><dllmap dll=\"x\"")
        }
        val builder = ProcessBuilder(
            listOf(LINKER, File(prefix, "bin/mono-sgen").absolutePath, "--version"),
        ).redirectErrorStream(true)
        builder.directory(context.filesDir)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
            put("MONO_CONFIG", broken.absolutePath)
        }
        val process = builder.start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(60, TimeUnit.SECONDS)

        Log.i(TAG, "broken MONO_CONFIG -> exit=${process.exitValue()}: ${text.take(200)}")
        assertTrue("mono produced nothing at all", text.isNotBlank())
    }

    private companion object {
        const val TAG = "MonoSpike"
        const val ARCHIVE = "mono.tar"
        const val LINKER = "/system/bin/linker64"

        /** The path Termux's build baked into `Interop.Sys`. */
        const val TERMUX_NATIVE = "/data/data/com.termux/files/usr/lib/../lib/libmono-native.so"
    }
}
