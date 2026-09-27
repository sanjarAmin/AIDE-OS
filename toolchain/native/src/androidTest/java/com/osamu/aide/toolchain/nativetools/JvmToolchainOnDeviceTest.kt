package com.osamu.aide.toolchain.nativetools

import android.content.Context
import android.util.Log
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
import java.util.concurrent.TimeUnit

/**
 * [JvmToolchain] against a real JDK, on a real device.
 *
 * Every workaround this class encodes exists because of platform behaviour, so
 * a fake would confirm nothing. The assertions are the observable effects: a
 * class this device compiled runs, and a JVM this device started can spawn a
 * child — which is what a build spends its time doing.
 *
 * Skipped when no JDK is staged. Build one with `tools/rootfs/fetch-jvm.sh` and
 * push `jvm.tar` to this package's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class JvmToolchainOnDeviceTest {

    private lateinit var context: Context
    private lateinit var jvm: JvmToolchain
    private lateinit var work: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "jvm")
        install(root)
        val javaHome = File(root, "lib/jvm/java-21-openjdk")
        assumeTrue(
            "no JDK staged: tools/rootfs/fetch-jvm.sh, then push jvm.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(javaHome, "lib/server/libjvm.so").isFile,
        )

        jvm = JvmToolchain.from(context, javaHome, DefaultDispatcherProvider())
        assertTrue("the JDK is staged but not usable", jvm.isInstalled)
        assertTrue("prepare failed", jvm.prepare() is AppResult.Success)

        work = File(context.filesDir, "jvm-toolchain-work").apply { deleteRecursively(); mkdirs() }
    }

    /** Unpacked by this process; see `tools/clang/FINDINGS.md` §4. */
    private fun install(root: File) {
        if (File(root, "lib/jvm/java-21-openjdk/lib/server/libjvm.so").isFile) return
        val archive = File(context.getExternalFilesDir(null), "jvm.tar")
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private suspend fun run(
        mainClass: String,
        classPath: List<File> = emptyList(),
        vmOptions: List<String> = emptyList(),
        arguments: List<String> = emptyList(),
    ): Pair<AppResult<ToolResult>, String> {
        val output = StringBuilder()
        val result = jvm.run(
            mainClass = mainClass,
            classPath = classPath,
            vmOptions = vmOptions,
            arguments = arguments,
            workingDir = work,
            environment = mapOf("TMPDIR" to work.absolutePath),
        ) { output.appendLine(it.text) }
        return result to output.toString().trim()
    }

    /**
     * A class this device compiled, run by the VM this device started.
     *
     * `javac` goes through the same launcher, dispatched by the name the
     * symlink was invoked under — so this exercises the tool path as well as
     * the `java` path.
     */
    @Test
    fun it_compiles_and_runs_a_class() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        File(work, "Hello.java").writeText(
            """
            public class Hello {
                public static void main(String[] args) {
                    System.out.println("$MARKER " + System.getProperty("java.version"));
                }
            }
            """.trimIndent(),
        )

        val (compiled, compileOutput) = run(
            mainClass = "com.sun.tools.javac.Main",
            arguments = listOf(File(work, "Hello.java").absolutePath, "-d", work.absolutePath),
        )
        Log.i(TAG, "javac: $compileOutput")
        assertTrue("javac could not start: $compiled", compiled is AppResult.Success)
        assertTrue("javac failed: $compileOutput", (compiled as AppResult.Success).value.isSuccess)
        assertTrue("no class file", File(work, "Hello.class").isFile)

        val (ran, output) = run(mainClass = "Hello", classPath = listOf(work))
        Log.i(TAG, "Hello: $output")
        assertTrue("running failed: $output", (ran as AppResult.Success).value.isSuccess)
        assertTrue("unexpected output: $output", output.startsWith(MARKER))
    }

    /**
     * **The spawn helper, which is what makes a build possible at all.**
     *
     * Gradle forks a daemon, the Kotlin plugin forks a compiler, AGP execs
     * `jlink`. All of it goes through the JVM's spawn helper, and the JDK's own
     * copy is in app storage where nothing may be executed. [JvmToolchain.prepare]
     * points it at the one this app ships; without that, this test fails with
     * `Failed to exec spawn helper` and every build fails with it too.
     */
    @Test
    fun a_jvm_it_started_can_spawn_a_child() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        File(work, "Fork.java").writeText(
            """
            public class Fork {
                public static void main(String[] args) throws Exception {
                    Process p = new ProcessBuilder("/system/bin/echo", "child-ran")
                        .redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes()).trim();
                    System.out.println("FORK " + p.waitFor() + " " + out);
                }
            }
            """.trimIndent(),
        )
        val (compiled, _) = run(
            mainClass = "com.sun.tools.javac.Main",
            arguments = listOf(File(work, "Fork.java").absolutePath, "-d", work.absolutePath),
        )
        assertTrue("javac failed", (compiled as AppResult.Success).value.isSuccess)

        val (ran, output) = run(mainClass = "Fork", classPath = listOf(work))

        Log.i(TAG, "fork: $output")
        assertTrue("the child did not run: $output", (ran as AppResult.Success).value.isSuccess)
        assertEquals("FORK 0 child-ran", output)
    }

    /**
     * `-jar`, which is how a Gradle wrapper starts.
     *
     * `gradlew` is a shell script whose real work is
     * `java -jar gradle-wrapper.jar`, so a project pinning its Gradle version
     * expects this to work. The main class comes from the jar's manifest, read
     * through `java.util.jar.JarFile` once the VM is up rather than by parsing
     * the zip in C — continuation lines and attribute sections are what a
     * hand-rolled reader gets wrong on the one jar that matters.
     *
     * The jar is assembled here rather than with the JDK's `jar` tool, so the
     * test depends on one thing at a time.
     */
    @Test
    fun it_runs_an_executable_jar() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        File(work, "Jarred.java").writeText(
            """
            public class Jarred {
                public static void main(String[] args) {
                    System.out.println("$MARKER from a jar " + args.length);
                }
            }
            """.trimIndent(),
        )
        val (compiled, compileOutput) = run(
            mainClass = "com.sun.tools.javac.Main",
            arguments = listOf(File(work, "Jarred.java").absolutePath, "-d", work.absolutePath),
        )
        assertTrue("javac failed: $compileOutput", (compiled as AppResult.Success).value.isSuccess)

        val jar = File(work, "jarred.jar")
        java.util.zip.ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\nMain-Class: Jarred\n\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("Jarred.class"))
            zip.write(File(work, "Jarred.class").readBytes())
            zip.closeEntry()
        }

        val output = StringBuilder()
        val result = jvm.runJar(
            jar = jar,
            arguments = listOf("one", "two"),
            workingDir = work,
            environment = mapOf("TMPDIR" to work.absolutePath),
        ) { output.appendLine(it.text) }

        Log.i(TAG, "-jar: ${output.toString().trim()}")
        assertTrue("running the jar failed: $output", (result as AppResult.Success).value.isSuccess)
        assertEquals("$MARKER from a jar 2", output.toString().trim())
    }

    /** A missing JDK is refused by name rather than failing somewhere inside. */
    @Test
    fun an_absent_runtime_is_refused_with_a_message() = runTest {
        val absent = JvmToolchain.from(
            context,
            File(context.filesDir, "no-such-jdk"),
            DefaultDispatcherProvider(),
        )

        val result = absent.run(mainClass = "Hello")

        assertTrue(result is AppResult.Failure)
        assertTrue(
            "the message does not say what is wrong: $result",
            (result as AppResult.Failure).error.message.contains("not installed"),
        )
    }

    private companion object {
        const val TAG = "JvmToolchain"
        const val MARKER = "AIDE-OS-JVM-TOOLCHAIN"
    }
}
