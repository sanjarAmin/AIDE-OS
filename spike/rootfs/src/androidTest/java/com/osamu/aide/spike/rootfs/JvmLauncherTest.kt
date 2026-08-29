package com.osamu.aide.spike.rootfs

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
 * Spike R11, third part: **a launcher of our own, and a JVM that starts.**
 *
 * [JvmOnDeviceTest] establishes that OpenJDK's `bin/java` cannot start here:
 * it re-execs through `/proc/self/exe`, which under this app's only launch
 * route is `/system/bin/linker64`. The launcher is not the VM, though — it is
 * a wrapper around `JNI_CreateJavaVM` in `libjvm.so`, and everything it does
 * to its own process is a convenience this app does not need.
 *
 * So the wrapper is replaced. `libjvmlauncher.so` is our own C, built by the
 * NDK, shipped in `jniLibs` and therefore extracted to `nativeLibraryDir` —
 * the one directory an app may execute from, which means it needs no linker
 * trick at all. It `dlopen`s `libjvm.so` out of app storage, which is
 * permitted (mapping is, executing is not — spike R9), and calls the entry
 * point directly. Nothing re-execs because nothing needs to.
 *
 * The JDK is staged out of band; see `tools/rootfs/fetch-jvm.sh`.
 */
@RunWith(AndroidJUnit4::class)
class JvmLauncherTest {

    private lateinit var context: Context
    private lateinit var javaHome: File
    private lateinit var launcher: File
    private lateinit var work: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "jvm")
        install(root)
        javaHome = File(root, "lib/jvm/java-21-openjdk")
        launcher = File(context.applicationInfo.nativeLibraryDir, "libjvmlauncher.so")

        assumeTrue(
            "no JVM staged: build one with tools/rootfs/fetch-jvm.sh and push " +
                "jvm.tar to ${context.getExternalFilesDir(null)}",
            File(javaHome, "lib/server/libjvm.so").exists(),
        )
        assertTrue("the launcher was not packaged into jniLibs", launcher.canExecute())

        work = File(context.filesDir, "launcher-work").apply { deleteRecursively(); mkdirs() }
    }

    private fun install(root: File) {
        if (File(root, "lib/jvm/java-21-openjdk/lib/server/libjvm.so").exists()) return
        val archive = File(context.getExternalFilesDir(null), "jvm.tar")
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    /**
     * Runs the launcher directly — no `linker64`, because
     * `nativeLibraryDir` is executable.
     *
     * `LD_LIBRARY_PATH` still matters: `libjvm.so` needs the JDK's own
     * libraries and `libz.so.1` from the Termux packages beside them, and
     * `dlopen` searches it just as `execve` would have.
     */
    private fun launch(vararg arguments: String, options: String? = null, timeoutSeconds: Long = 300): Run {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(listOf(launcher.absolutePath) + arguments)
            .redirectErrorStream(true)
        builder.directory(work)
        builder.environment().apply {
            put(
                "LD_LIBRARY_PATH",
                listOf(
                    File(javaHome, "lib/server"),
                    File(javaHome, "lib"),
                    File(context.filesDir, "jvm/lib"),
                ).joinToString(":") { it.absolutePath },
            )
            put("HOME", work.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            options?.let { put("JVM_OPTIONS", it) }
        }
        val process = builder.start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Run(-1, "timed out after ${timeoutSeconds}s", System.currentTimeMillis() - started)
        }
        return Run(process.exitValue(), text, System.currentTimeMillis() - started)
    }

    /**
     * Compiles with the JDK's own javac, through the launcher.
     *
     * No classpath is set: `com.sun.tools.javac.Main` lives in the
     * `jdk.compiler` module, which the VM resolves from `java.home` — the same
     * reason `java com.sun.tools.javac.Main` works on an ordinary JDK without
     * anything on the classpath.
     */
    private fun compile(source: File): Run = launch(
        javaHome.absolutePath,
        "com.sun.tools.javac.Main",
        source.absolutePath,
        "-d", work.absolutePath,
    )

    @Test
    fun the_launcher_ships_where_it_can_be_executed() {
        Log.i(TAG, "launcher at ${launcher.absolutePath}, ${launcher.length()} bytes")
        assertTrue("not in nativeLibraryDir", launcher.absolutePath.contains("lib"))
        assertTrue("not executable", launcher.canExecute())
    }

    /**
     * **The one that decides M9.**
     *
     * A class this test wrote, compiled by nothing and run by the VM: the
     * launcher creates the VM, finds the class on the classpath it was given
     * and calls `main`. If this passes, the Gradle path needs no Linux
     * userland — only a JDK and this launcher.
     */
    @Test
    fun it_creates_a_vm_and_runs_a_class() {
        // Precompiled bytecode would prove less: the class has to come from
        // this device. javac is inside the JDK's own modules, so the launcher
        // runs it the same way it will run anything else.
        val source = File(work, "Hello.java").apply {
            writeText(
                """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("$MARKER " + System.getProperty("java.version")
                            + " on " + System.getProperty("os.arch"));
                    }
                }
                """.trimIndent(),
            )
        }

        val compiled = compile(source)
        Log.i(TAG, "javac in ${compiled.millis} ms: exit=${compiled.exit} ${compiled.output.take(400)}")
        assertTrue("javac failed: ${compiled.output}", compiled.exit == 0)
        assertTrue("no class file was produced", File(work, "Hello.class").isFile)

        val ran = launch(
            javaHome.absolutePath,
            "Hello",
            options = "-Djava.class.path=${work.absolutePath}",
        )
        Log.i(TAG, "Hello in ${ran.millis} ms: exit=${ran.exit} ${ran.output.take(200)}")
        assertTrue("running the class failed: ${ran.output}", ran.exit == 0)
        assertTrue("unexpected output: ${ran.output}", ran.output.startsWith(MARKER))
    }

    /**
     * **The VM can fork, but only if told how.**
     *
     * Gradle's normal mode is a daemon it forks, so this decides whether M9 can
     * use Gradle as it ships. The default answer looks like "no":
     *
     * ```
     * java.io.IOException: Cannot run program "/system/bin/echo":
     *   Failed to exec spawn helper
     * ```
     *
     * That is not the platform refusing to spawn. On Linux the JVM's default
     * launch mechanism is `POSIX_SPAWN`, which runs a small JDK binary called
     * `jspawnhelper` and has *it* exec the target — and `jspawnhelper` lives in
     * app-private storage, which is exactly what may not be executed. The
     * failure is about the helper, not the child.
     *
     * `-Djdk.lang.Process.launchMechanism=vfork` skips the helper and execs the
     * target directly. `/system/bin/echo` is in an executable location, so it
     * runs.
     *
     * **The constraint is therefore what gets exec'd, not that anything is.**
     * A child in `/system/bin` is fine; a child in app storage is not — which
     * means a Gradle daemon, being another `java`, still needs the launcher
     * rather than the JDK's own.
     */
    @Test
    fun the_vm_can_fork_only_with_a_launch_mechanism_that_skips_the_helper() {
        val source = File(work, "Fork.java").apply {
            writeText(
                """
                public class Fork {
                    public static void main(String[] args) throws Exception {
                        try {
                            Process p = new ProcessBuilder("/system/bin/echo", "child-ran")
                                .redirectErrorStream(true).start();
                            String out = new String(p.getInputStream().readAllBytes()).trim();
                            System.out.println("FORK-OK " + p.waitFor() + " " + out);
                        } catch (Throwable t) {
                            System.out.println("FORK-FAILED " + t);
                        }
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue("javac failed", compile(source).exit == 0)
        val classpath = "-Djava.class.path=${work.absolutePath}"

        val byDefault = launch(javaHome.absolutePath, "Fork", options = classpath)
        Log.i(TAG, "default mechanism: ${byDefault.output.take(200)}")
        assertTrue(
            "the default mechanism worked, so jspawnhelper is now executable " +
                "and the vfork workaround is unnecessary: ${byDefault.output}",
            "FORK-FAILED" in byDefault.output,
        )
        assertTrue(
            "it failed for some reason other than the spawn helper: ${byDefault.output}",
            "spawn helper" in byDefault.output,
        )

        val withVfork = launch(
            javaHome.absolutePath,
            "Fork",
            options = "$classpath -Djdk.lang.Process.launchMechanism=vfork",
        )
        Log.i(TAG, "vfork: ${withVfork.output.take(200)}")
        assertTrue("vfork could not spawn either: ${withVfork.output}", "FORK-OK" in withVfork.output)
        assertTrue("the child produced nothing: ${withVfork.output}", "child-ran" in withVfork.output)
    }

    private companion object {
        const val TAG = "JvmLauncher"
        const val MARKER = "AIDE-OS-JVM-RAN"
    }
}
