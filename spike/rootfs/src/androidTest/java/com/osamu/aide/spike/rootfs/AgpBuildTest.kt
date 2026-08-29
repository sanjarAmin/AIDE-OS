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
import java.util.zip.ZipFile

/**
 * Spike R11, fifth part: **the Android Gradle Plugin, on the device.**
 *
 * M9's acceptance test is "an unmodified Android Studio project builds", and
 * this is how close that is. Gradle resolves AGP 9.3.2 from Google's Maven,
 * applies it, and runs the whole pipeline — `processDebugResources` through
 * aapt2, `dexBuilderDebug` through D8, `packageDebug` — producing an APK.
 *
 * Two substitutions make it possible, and both are the same trick:
 *
 *  - `<jdk>/bin/java` is a symlink to our launcher, so Gradle's daemon fork
 *    execs something it is allowed to (see [GradleOnDeviceTest]).
 *  - `aapt2` is a symlink to the `libaapt2.so` this APK ships, because the
 *    aapt2 AGP fetches from Maven is a **Linux x86_64 binary** and cannot run
 *    on Android at all. AGP takes `android.aapt2FromMavenOverride`, and insists
 *    the path be named `aapt2` — hence the link rather than the `.so` directly.
 *
 * The SDK is staged out of band: `platforms/android-36`, `build-tools/36.0.0`
 * and an accepted licence, under `files/android-sdk/sdk36`.
 *
 * **The Gradle user home is deliberately shared and not cleaned.** The first
 * run downloads AGP and its dependencies; wiping it would make every run a
 * network test of Google's Maven rather than a test of this device.
 */
@RunWith(AndroidJUnit4::class)
class AgpBuildTest {

    private lateinit var context: Context
    private lateinit var javaHome: File
    private lateinit var launcher: File
    private lateinit var gradleHome: File
    private lateinit var sdk: File
    private lateinit var project: File
    private lateinit var support: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        javaHome = File(context.filesDir, "jvm/lib/jvm/java-21-openjdk")
        launcher = File(context.applicationInfo.nativeLibraryDir, "libjvmlauncher.so")
        gradleHome = File(context.filesDir, "gradle").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("gradle-") }
            ?: File(context.filesDir, "gradle/none")
        sdk = File(context.filesDir, "android-sdk/sdk36")

        assumeTrue("no JDK staged", File(javaHome, "lib/server/libjvm.so").exists())
        assumeTrue("no Gradle staged", File(gradleHome, "lib").isDirectory)
        assumeTrue("no Android SDK staged", File(sdk, "platforms/android-36/android.jar").isFile)

        support = File(context.filesDir, "agp-support").apply { mkdirs() }
        File(support, "tmp").mkdirs()
        project = File(context.filesDir, "agp-project").apply { deleteRecursively(); mkdirs() }
        writeProject()
        redirectJavaToLauncher()
    }

    /** Leaves the JDK as it was found; see [GradleOnDeviceTest.restoreJava]. */
    @After
    fun restoreJava() {
        JDK_TOOLS.forEach { tool ->
            val real = File(javaHome, "bin/$tool")
            val kept = File(javaHome, "bin/$tool.real")
            if (kept.exists()) {
                real.delete()
                kept.renameTo(real)
            }
        }
    }

    private fun redirectJavaToLauncher() {
        JDK_TOOLS.forEach { tool ->
            val real = File(javaHome, "bin/$tool")
            val kept = File(javaHome, "bin/$tool.real")
            if (!real.exists() && !Files.isSymbolicLink(real.toPath())) return@forEach
            if (!kept.exists() && !Files.isSymbolicLink(real.toPath())) real.renameTo(kept)
            real.delete()
            Files.createSymbolicLink(real.toPath(), launcher.toPath())
        }
        Log.i(TAG, JDK_TOOLS.joinToString(" ") { tool ->
            val f = File(javaHome, "bin/$tool")
            "$tool=" + (if (Files.isSymbolicLink(f.toPath())) "link" else if (f.exists()) "file" else "absent")
        })
    }

    /**
     * `aapt2`, under the name AGP demands, pointing at the one that runs here.
     *
     * A symlink rather than a copy: the target has to stay in
     * `nativeLibraryDir` to be executable, and the kernel checks the resolved
     * file.
     */
    private fun aapt2(): File {
        val bin = File(support, "bin").apply { mkdirs() }
        val link = File(bin, "aapt2")
        link.delete()
        Files.createSymbolicLink(
            link.toPath(),
            File(context.applicationInfo.nativeLibraryDir, "libaapt2.so").toPath(),
        )
        return link
    }

    private fun writeProject() {
        File(project, "src/main/java/demo/app").mkdirs()
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "agpdemo"
            """.trimIndent(),
        )
        File(project, "build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") version "9.3.2" }
            android {
                namespace = "demo.app"
                compileSdk { version = release(36) }
                defaultConfig {
                    applicationId = "demo.app"
                    minSdk = 26
                    versionCode = 1
                }
            }
            """.trimIndent(),
        )
        File(project, "src/main/AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="agpdemo" />
            </manifest>
            """.trimIndent(),
        )
        File(project, "src/main/java/demo/app/Hello.java").writeText(
            "package demo.app;\npublic class Hello { public static String greet() { return \"$MARKER\"; } }\n",
        )
        File(project, "local.properties").writeText("sdk.dir=${sdk.absolutePath}\n")
        File(project, "gradle.properties").writeText(
            // **The daemon needs the vfork mechanism too**, not only the
            // client. AGP execs jlink from inside the daemon, and the
            // default POSIX_SPAWN runs jspawnhelper out of app storage,
            // which cannot be executed. org.gradle.jvmargs is what reaches
            // the daemon; -D on the client does not.
            "android.aapt2FromMavenOverride=${aapt2().absolutePath}\n" +
                "org.gradle.jvmargs=-Xmx1g -Djdk.lang.Process.launchMechanism=vfork\n" +
                // Gradle drops a bare -D from org.gradle.jvmargs when it builds
                // the daemon's command line; `systemProp.` is the channel that
                // reaches it.
                "systemProp.jdk.lang.Process.launchMechanism=vfork\n",
        )
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    private fun gradle(vararg arguments: String, timeoutSeconds: Long = 1800): Run {
        val started = System.currentTimeMillis()
        val launcherJar = File(gradleHome, "lib").listFiles()
            ?.firstOrNull { it.name.startsWith("gradle-launcher-") }
            ?: error("no gradle-launcher jar")

        val builder = ProcessBuilder(
            listOf(
                launcher.absolutePath,
                "-cp", launcherJar.absolutePath,
                "-Djdk.lang.Process.launchMechanism=vfork",
                "-Duser.home=${support.absolutePath}",
                "-Djava.io.tmpdir=${File(support, "tmp").absolutePath}",
                "org.gradle.launcher.GradleMain",
            ) + arguments + listOf("--no-daemon", "-g", File(support, "guh").absolutePath),
        ).redirectErrorStream(true)
        builder.directory(project)
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
            put("ANDROID_HOME", sdk.absolutePath)
            put("HOME", support.absolutePath)
            put("TMPDIR", File(support, "tmp").absolutePath)
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
     * **AGP runs, and stops in one place.**
     *
     * Under `adb shell run-as` this build succeeds: 33 tasks, aapt2 through
     * `processDebugResources`, D8 through `dexBuilderDebug`, and an 871 KB APK
     * with a binary manifest and dex in it.
     *
     * In the app's own process it gets thirteen tasks in and fails at AGP's
     * `JdkImageTransform`, which execs `jlink` to build a system-modules image
     * from `core-for-system-modules.jar`:
     *
     * ```
     * Cannot run program ".../bin/jlink": Failed to exec spawn helper
     * ```
     *
     * **The symlink is not the problem** — `bin/jlink` is a link to the
     * launcher here, and running it by hand works. The problem is `jspawnhelper`
     * again: the exec happens *inside the Gradle daemon*, whose JVM does not
     * have `-Djdk.lang.Process.launchMechanism=vfork`. Passing it through
     * `org.gradle.jvmargs` does not reach the daemon's command line, and
     * `systemProp.` arrives too late to matter — `java.lang.ProcessImpl` reads
     * that property when it initialises.
     *
     * So the remaining work is to get the daemon started with that option, or
     * to make `jspawnhelper` executable by shipping it in `jniLibs` and
     * symlinking the JDK's copy at it — the same trick as `java` and `aapt2`,
     * applied once more.
     *
     * Asserted as a failure, and asserted *specifically*, so that a different
     * failure is not mistaken for this one.
     */
    @Test
    fun agp_runs_and_stops_at_the_jdk_image_transform() {
        val run = gradle("assembleDebug", "--stacktrace")

        Log.i(TAG, "assembleDebug in ${run.millis} ms: exit=${run.exit}")
        run.output.takeLast(2600).chunked(900).forEach { Log.i(TAG, it) }
        assertTrue(
            "AGP built an APK in this process. The daemon can now exec what it " +
                "needs and this test should become the positive one.",
            run.exit != 0,
        )
        assertTrue(
            "AGP failed somewhere other than the JDK image transform, which is " +
                "worth reading rather than assuming: ${run.output.takeLast(900)}",
            "JdkImageTransform" in run.output,
        )
        assertTrue(
            "the transform failed for a reason other than the spawn helper: " +
                run.output.takeLast(900),
            "spawn helper" in run.output,
        )
        // How far it does get: AGP resolved from Google's Maven, applied, and
        // ran a dozen tasks. That is the part worth not losing.
        assertTrue(
            "AGP did not even configure: ${run.output.takeLast(600)}",
            "actionable tasks" in run.output,
        )
    }

    private companion object {
        const val TAG = "AgpSpike"

        /**
         * Every JDK binary a build might exec, redirected to the launcher.
         *
         * `java` for Gradle's daemon; `jlink` because AGP's JdkImageTransform
         * builds a system-modules image from `core-for-system-modules.jar` and
         * shells out to it. The rest are here because they are the same kind of
         * file -- a launcher stub that cannot run from app storage -- and
         * finding that out one failed build at a time is slower than listing
         * them.
         */
        val JDK_TOOLS = listOf("java", "jlink", "javac", "jar", "javadoc", "jdeps", "jmod")
        const val MARKER = "built by AGP on device"
    }
}
