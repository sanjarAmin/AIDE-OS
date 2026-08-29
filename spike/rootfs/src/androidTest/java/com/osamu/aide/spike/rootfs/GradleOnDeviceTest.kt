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
 * Spike R11, fourth part: **Gradle, in the app's own process.**
 *
 * The launcher makes a JVM run ([JvmLauncherTest]); Gradle is what M9 actually
 * needs. It runs under `adb shell run-as` — 9.7.1, a `java-library` project
 * compiled to a jar in ten seconds — but that proves nothing on its own, and
 * this is the third time in this spike that `run-as` has answered a question
 * more favourably than the app can. `tools/clang/FINDINGS.md` §7.
 *
 * Under `run-as` Gradle forks a single-use daemon and it starts. It should not
 * be able to: the daemon is another `java`, in app-private storage, and that is
 * what may not be executed. So this asks the same question where the answer
 * counts.
 *
 * Staged out of band: `tools/rootfs/fetch-jvm.sh` for the JDK, and the Gradle
 * binary distribution unpacked into `files/gradle`.
 */
@RunWith(AndroidJUnit4::class)
class GradleOnDeviceTest {

    private lateinit var context: Context
    private lateinit var javaHome: File
    private lateinit var launcher: File
    private lateinit var gradleHome: File
    private lateinit var work: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        javaHome = File(context.filesDir, "jvm/lib/jvm/java-21-openjdk")
        launcher = File(context.applicationInfo.nativeLibraryDir, "libjvmlauncher.so")
        gradleHome = File(context.filesDir, "gradle").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("gradle-") }
            ?: File(context.filesDir, "gradle/none")

        assumeTrue(
            "no JDK staged; see tools/rootfs/fetch-jvm.sh",
            File(javaHome, "lib/server/libjvm.so").exists(),
        )
        assumeTrue(
            "no Gradle staged in ${File(context.filesDir, "gradle")}",
            File(gradleHome, "lib").isDirectory,
        )

        work = File(context.filesDir, "gradle-test").apply { deleteRecursively(); mkdirs() }
        File(work, "tmp").mkdirs()
        writeProject()
    }

    private fun writeProject() {
        val project = File(work, "proj/src/main/java/demo").apply { mkdirs() }
        File(work, "proj/settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        // `java-library` only: no repositories are declared and nothing is
        // resolved, so the build needs no network and cannot be slowed by one.
        File(work, "proj/build.gradle.kts").writeText("plugins { id(\"java-library\") }\n")
        File(project, "Greeter.java").writeText(
            "package demo;\npublic class Greeter { public String hello() { return \"built on device\"; } }\n",
        )
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    private fun gradle(vararg arguments: String, timeoutSeconds: Long = 900): Run {
        val started = System.currentTimeMillis()
        val launcherJar = File(gradleHome, "lib").listFiles()
            ?.firstOrNull { it.name.startsWith("gradle-launcher-") }
            ?: error("no gradle-launcher jar in ${File(gradleHome, "lib")}")

        val options = listOf(
            "-Djava.class.path=${launcherJar.absolutePath}",
            // Without this the VM cannot spawn at all: the default mechanism
            // runs jspawnhelper out of app storage. See JvmLauncherTest.
            "-Djdk.lang.Process.launchMechanism=vfork",
            "-Duser.home=${work.absolutePath}",
            // The Termux JDK bakes in Termux's own prefix as java.io.tmpdir,
            // and that directory does not exist here. Gradle fails deep inside
            // service construction if it is left alone.
            "-Djava.io.tmpdir=${File(work, "tmp").absolutePath}",
        ).joinToString(" ")

        val builder = ProcessBuilder(
            listOf(launcher.absolutePath, javaHome.absolutePath, "org.gradle.launcher.GradleMain") +
                arguments + listOf("--no-daemon", "-g", File(work, "guh").absolutePath, "--offline"),
        ).redirectErrorStream(true)
        builder.directory(File(work, "proj"))
        builder.environment().apply {
            put(
                "LD_LIBRARY_PATH",
                listOf(
                    File(javaHome, "lib/server"),
                    File(javaHome, "lib"),
                    File(context.filesDir, "jvm/lib"),
                ).joinToString(":") { it.absolutePath },
            )
            put("JVM_OPTIONS", options)
            put("HOME", work.absolutePath)
            put("TMPDIR", File(work, "tmp").absolutePath)
        }
        val process = builder.start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Run(-1, "timed out after ${timeoutSeconds}s\n$text", System.currentTimeMillis() - started)
        }
        return Run(process.exitValue(), text, System.currentTimeMillis() - started)
    }

    @Test
    fun gradle_reports_its_version() {
        val run = gradle("--version")

        Log.i(TAG, "gradle --version in ${run.millis} ms: exit=${run.exit}")
        Log.i(TAG, run.output.lines().filter { it.contains("Gradle ") || it.contains("JVM") }.joinToString(" | "))
        assertTrue("gradle did not start: ${run.output.take(600)}", run.exit == 0)
        assertTrue("no version banner: ${run.output.take(400)}", "Gradle 9" in run.output)
    }

    /**
     * **Gradle runs, and cannot yet build, in the app's own process.**
     *
     * Under `adb shell run-as` this same command succeeds: 9.7.1 compiles the
     * `java-library` project and writes `demo.jar` in about ten seconds. Here
     * it fails:
     *
     * ```
     * To honour the JVM settings for this build a single-use Daemon process
     * will be forked.
     * A problem occurred starting process 'Gradle build daemon'
     * ```
     *
     * The daemon is another `java`, in app-private storage, and that is what
     * may not be executed — `run-as` may, which is the whole difference and the
     * third time in this spike that domain has flattered the result.
     *
     * `--no-daemon` does not prevent it: Gradle still forks a *single-use*
     * daemon whenever it decides the client JVM does not match the settings the
     * build wants. Passing matching `-Xmx`/`-XX:MaxMetaspaceSize` does not stop
     * it either, and Gradle reports `There is no native integration with this
     * operating environment` — its native-platform library has no Android
     * support, so it cannot inspect the client to conclude a fork is
     * unnecessary. That the two are connected is plausible and **not
     * established here**.
     *
     * Asserted as a failure so that it is noticed the day it stops being one.
     */
    @Test
    fun a_build_still_needs_a_daemon_it_cannot_fork() {
        val run = gradle("build")

        Log.i(TAG, "gradle build in ${run.millis} ms: exit=${run.exit}")
        Log.i(TAG, run.output.takeLast(500))
        assertTrue(
            "Gradle built without forking a daemon. The route below is no " +
                "longer needed and this test should become the positive one: " +
                run.output.takeLast(400),
            run.exit != 0,
        )
        assertTrue(
            "it failed for some reason other than the daemon: ${run.output.takeLast(500)}",
            "daemon" in run.output.lowercase(),
        )
        // The jar is the thing M9 actually wants, and it is not there.
        assertTrue(
            "a jar appeared despite the failure, so the daemon is not the " +
                "blocker after all",
            !File(work, "proj/build/libs/demo.jar").isFile,
        )
    }

    private companion object {
        const val TAG = "GradleSpike"
    }
}
