package com.osamu.aide.spike.rootfs

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spike R11, second half: **a real JVM, without a rootfs.**
 *
 * [ProotOnDeviceTest] closes the route `docs/PLAN.md` assumed for M9 — PRoot
 * works, but a musl guest cannot be executed by an app that may not `execve`
 * its own files. That looked like a dead end for the Gradle path, since Gradle
 * needs a JVM and ART is not one.
 *
 * It is not, because the premise was wrong. The point of the rootfs was to
 * obtain a JVM, and Termux publishes OpenJDK built **against Bionic**, as an
 * ordinary Android ELF with `/system/bin/linker64` as its interpreter. That is
 * precisely the shape spike R9 established can be started, and R10 has been
 * running clang that way since. No guest, no PRoot, no second libc.
 *
 * So this asks the question M9 actually depends on, in the app's own process,
 * because that is the only place an exec question is settled
 * (`tools/clang/FINDINGS.md` §7).
 */
@RunWith(AndroidJUnit4::class)
class JvmOnDeviceTest {

    private lateinit var context: Context
    private lateinit var javaHome: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "jvm")
        install(root)
        javaHome = File(root, "lib/jvm/java-21-openjdk")
        assumeTrue(
            "no JVM staged: see tools/rootfs/fetch-jvm.sh, then push jvm.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(javaHome, "bin/java").exists(),
        )
    }

    private fun install(root: File) {
        if (File(root, "lib/jvm/java-21-openjdk/bin/java").exists()) return
        val archive = File(context.getExternalFilesDir(null), ARCHIVE)
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    /**
     * Starts `java` through the dynamic linker.
     *
     * **`LD_LIBRARY_PATH` must already contain the JDK's own directories**, and
     * this is not merely about finding libraries. OpenJDK's launcher re-execs
     * itself when it decides the path needs fixing, and under this launch
     * `/proc/self/exe` is the linker — so the re-exec becomes
     * `linker64 -version` and dies with `expected absolute path`. Set the path
     * up front and the launcher has nothing to correct, so it never re-execs.
     * The same failure clang has when it spawns `cc1`, and the same cure.
     */
    private fun java(vararg arguments: String, timeoutSeconds: Long = 180): Run {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(
            listOf("/system/bin/linker64", File(javaHome, "bin/java").absolutePath) + arguments,
        ).redirectErrorStream(true)
        builder.directory(context.filesDir)
        builder.environment().apply {
            put(
                "LD_LIBRARY_PATH",
                listOf(
                    File(javaHome, "lib/server"),
                    File(javaHome, "lib"),
                    File(context.filesDir, "jvm/lib"),
                ).joinToString(":") { it.absolutePath },
            )
            put("JAVA_HOME", javaHome.absolutePath)
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
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

    @Test
    fun the_jvm_is_installed_and_is_an_android_binary() {
        val launcher = File(javaHome, "bin/java")
        assertTrue("no java launcher", launcher.canRead())
        assertTrue("no server VM", File(javaHome, "lib/server/libjvm.so").canRead())
        Log.i(TAG, "java launcher is ${launcher.length()} bytes")
    }

    /**
     * **The JVM starts under `run-as` and does not start in the app.**
     *
     * Both were measured with byte-for-byte identical environments — the same
     * `LD_LIBRARY_PATH`, dumped by `env(1)` from this very process to be sure —
     * and the same command. Under `adb shell run-as` the VM reports
     * `OpenJDK 64-Bit Server VM (build 21.0.12, mixed mode)`. Here it dies:
     *
     * ```
     * error: expected absolute path: "-version"
     * ```
     *
     * That message is the linker's. OpenJDK's launcher re-execs itself through
     * `/proc/self/exe`, which under this launch is `/system/bin/linker64`, so
     * the re-exec becomes `linker64 -version`. With no arguments at all the
     * failure is plainer still: what comes back is the *linker's* usage text
     * wearing java's path as `argv[0]`.
     *
     * The launcher only re-execs when it wants to change something about its
     * own process. Since the environment is identical, whatever it wants
     * differs between `runas_app` and `untrusted_app` — the primordial thread's
     * stack is the obvious candidate and is not established here.
     *
     * **So this is asserted as a failure, and `run-as` is not evidence against
     * it.** `tools/clang/FINDINGS.md` §7 is exactly this trap: the permissive
     * domain answered the question differently and would have had M9 built on
     * a result the app cannot reproduce. The route out is not to argue with the
     * launcher but to replace it — a small launcher of our own calling
     * `JNI_CreateJavaVM` never re-execs, and M7 shipped a C compiler that can
     * build one.
     */
    @Test
    fun the_launcher_re_execs_and_dies_in_this_process() {
        val run = java("-version")

        Log.i(TAG, "java -version in ${run.millis} ms: exit=${run.exit} ${run.output.take(200)}")
        assertTrue(
            "the JVM started from the app process. The launcher no longer " +
                "re-execs, so M9 can use `java` directly and this test should " +
                "become the positive one: ${run.output}",
            run.exit != 0,
        )
        assertTrue(
            "it failed for some reason other than the re-exec, which is worth " +
                "looking at rather than assuming: ${run.output}",
            "expected absolute path" in run.output,
        )
    }

    private companion object {
        const val TAG = "JvmSpike"
        const val ARCHIVE = "jvm.tar"
    }
}
