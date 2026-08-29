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
        redirectSpawnHelper()
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
        val helper = File(javaHome, "lib/jspawnhelper")
        val keptHelper = File(javaHome, "lib/jspawnhelper.real")
        if (keptHelper.exists()) {
            helper.delete()
            keptHelper.renameTo(helper)
        }
    }

    /**
     * Points the JDK's `jspawnhelper` at the copy this APK ships.
     *
     * The JVM's default `POSIX_SPAWN` runs this helper and has *it* exec the
     * target, and the JDK's own copy is in app-private storage — so every
     * `ProcessBuilder` inside a build fails with `Failed to exec spawn helper`.
     * Ours is in `nativeLibraryDir`, which is executable, and the kernel checks
     * the resolved file.
     *
     * This is better than passing `-Djdk.lang.Process.launchMechanism=vfork`:
     * that option has to reach every JVM a build starts, and Gradle gives no
     * reliable way to put it on the daemon's command line. Fixing the helper
     * fixes them all at once, including JVMs nobody here launches.
     */
    private fun redirectSpawnHelper() {
        val shipped = File(context.applicationInfo.nativeLibraryDir, "libjspawnhelper.so")
        if (!shipped.canExecute()) return
        val real = File(javaHome, "lib/jspawnhelper")
        val kept = File(javaHome, "lib/jspawnhelper.real")
        if (!kept.exists() && real.exists() && !Files.isSymbolicLink(real.toPath())) {
            real.renameTo(kept)
        }
        real.delete()
        Files.createSymbolicLink(real.toPath(), shipped.toPath())
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
     * **M9's acceptance question: an Android project builds, on the device.**
     *
     * AGP 9.3.2 resolves from Google's Maven, applies, and runs the whole
     * pipeline — aapt2 through `processDebugResources`, D8 through
     * `dexBuilderDebug`, `packageDebug` — in this app's own process.
     *
     * Four substitutions make it possible, and three are the same idea: a
     * symlink from where a tool is expected to a copy somewhere the app may
     * execute, since the kernel checks the *resolved* file.
     *
     *  - `bin/java` and the other JDK tools point at our launcher, so Gradle's
     *    daemon fork and AGP's `jlink` call start something legal.
     *  - `lib/jspawnhelper` points at the copy this APK ships. The JVM's
     *    default `POSIX_SPAWN` runs that helper and has *it* exec the target;
     *    the JDK's own copy is in app storage, so every `ProcessBuilder` in a
     *    build failed with `Failed to exec spawn helper`.
     *  - `aapt2` points at our `libaapt2.so`, because the aapt2 AGP fetches
     *    from Maven is a Linux x86_64 binary that cannot run here at all.
     *  - The SDK is staged in app storage and named in `local.properties`.
     *
     * The APK's contents are asserted rather than the exit code: a build that
     * skipped every task also exits zero.
     */
    @Test
    fun agp_builds_an_android_apk() {
        val run = gradle("assembleDebug")

        Log.i(TAG, "assembleDebug in ${run.millis} ms: exit=${run.exit}")
        run.output.takeLast(1800).chunked(900).forEach { Log.i(TAG, it) }
        assertTrue("the build failed:\n${run.output.takeLast(1500)}", run.exit == 0)

        val apk = File(project, "build/outputs/apk/debug/agpdemo-debug.apk")
        assertTrue("no APK was produced", apk.isFile)

        val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(
            "no binary manifest, so aapt2 did not run: ${entries.take(20)}",
            "AndroidManifest.xml" in entries,
        )
        assertTrue(
            "no dex, so D8 did not run: ${entries.take(20)}",
            entries.any { it.startsWith("classes") && it.endsWith(".dex") },
        )
        Log.i(TAG, "APK is ${apk.length()} bytes, ${entries.size} entries")
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
