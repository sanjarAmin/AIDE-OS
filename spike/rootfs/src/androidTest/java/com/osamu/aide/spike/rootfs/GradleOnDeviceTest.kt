package com.osamu.aide.spike.rootfs

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
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

    /**
     * Puts `bin/java` back.
     *
     * [redirectJavaToLauncher] edits the staged JDK, which every test in this
     * module shares. Leaving the symlink behind made `JvmOnDeviceTest`'s
     * assertion that the *stock* launcher re-execs fail -- it was looking at
     * ours. A test that changes shared device state has to undo it, or the
     * failure surfaces somewhere that never mentions this file.
     */
    @After
    fun restoreJava() {
        val real = File(javaHome, "bin/java")
        val kept = File(javaHome, "bin/java.real")
        if (kept.exists()) {
            real.delete()
            kept.renameTo(real)
        }
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    private fun gradle(vararg arguments: String, timeoutSeconds: Long = 900): Run {
        val started = System.currentTimeMillis()
        val launcherJar = File(gradleHome, "lib").listFiles()
            ?.firstOrNull { it.name.startsWith("gradle-launcher-") }
            ?: error("no gradle-launcher jar in ${File(gradleHome, "lib")}")

        val builder = ProcessBuilder(
            listOf(
                launcher.absolutePath,
                "-cp", launcherJar.absolutePath,
                // Without this the VM cannot spawn at all: the default
                // mechanism runs jspawnhelper out of app storage.
                "-Djdk.lang.Process.launchMechanism=vfork",
                "-Duser.home=${work.absolutePath}",
                // The Termux JDK bakes in Termux's own prefix as
                // java.io.tmpdir, and that directory does not exist here.
                "-Djava.io.tmpdir=${File(work, "tmp").absolutePath}",
                "org.gradle.launcher.GradleMain",
            ) + arguments + listOf("--no-daemon", "-g", File(work, "guh").absolutePath, "--offline"),
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
            put("JAVA_HOME", javaHome.absolutePath)
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

    /**
     * Puts the launcher where Gradle will look for `java`.
     *
     * Gradle starts its daemon by exec'ing `$java.home/bin/java`, which is in
     * app-private storage and cannot be executed. Replacing it with a symlink
     * to the launcher makes that exec legal, because **the kernel checks the
     * resolved file** and the launcher is in `nativeLibraryDir`. The launcher
     * takes `java`'s arguments, and derives `java.home` from `argv[0]` — which
     * under this symlink is the JDK path Gradle passed, not the launcher's own.
     *
     * The real launcher is kept beside it as `java.real`, so the substitution
     * is visible to anyone looking and reversible.
     */
    private fun redirectJavaToLauncher() {
        val real = File(javaHome, "bin/java")
        val kept = File(javaHome, "bin/java.real")
        if (!kept.exists() && real.exists() && !Files.isSymbolicLink(real.toPath())) {
            real.renameTo(kept)
        }
        real.delete()
        Files.createSymbolicLink(real.toPath(), launcher.toPath())
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
     * **The acceptance question for M9's foundation: Gradle builds.**
     *
     * Gradle starts its daemon by exec'ing `$java.home/bin/java`, which is in
     * app-private storage and may not be executed — so a build failed here
     * while succeeding under `run-as`, which is allowed to do exactly that.
     *
     * [redirectJavaToLauncher] closes the gap without asking for any new
     * permission: the kernel checks the *resolved* file of a symlink, and the
     * launcher lives in `nativeLibraryDir`, which is executable. Gradle execs
     * the path it always would; what runs is a launcher that does not re-exec.
     *
     * The jar is asserted rather than the exit code, because a build that
     * skipped every task also exits zero.
     */
    @Test
    fun it_builds_a_java_project_into_a_jar() {
        redirectJavaToLauncher()

        val run = gradle("build")

        Log.i(TAG, "gradle build in ${run.millis} ms: exit=${run.exit}")
        Log.i(TAG, run.output.takeLast(600))
        assertTrue("the build failed: ${run.output.takeLast(900)}", run.exit == 0)

        val jar = File(work, "proj/build/libs/demo.jar")
        assertTrue("no jar was produced: ${run.output.takeLast(400)}", jar.isFile)

        val entries = java.util.zip.ZipFile(jar).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("the jar has no compiled class: $entries", "demo/Greeter.class" in entries)
    }

    private companion object {
        const val TAG = "GradleSpike"
    }
}
