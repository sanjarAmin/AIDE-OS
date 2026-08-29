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

    private fun writeProject(withAndroidX: Boolean = false) {
        File(project, "src/main/java/demo/app").mkdirs()
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "agpdemo"
            """.trimIndent(),
        )
        File(project, "build.gradle.kts").writeText(
            buildString {
                appendLine("""plugins { id("com.android.application") version "9.3.2" }""")
                appendLine("android {")
                appendLine("""    namespace = "demo.app"""")
                appendLine("    compileSdk { version = release(36) }")
                appendLine("    defaultConfig {")
                appendLine("""        applicationId = "demo.app"""")
                appendLine("        minSdk = 26")
                appendLine("        versionCode = 1")
                appendLine("    }")
                appendLine("}")
                if (withAndroidX) {
                    // A real dependency, resolved from Google's Maven: an AAR
                    // with its own resources and manifest, which is what makes
                    // resource merging and manifest merging actually happen.
                    appendLine("""dependencies { implementation("androidx.appcompat:appcompat:1.7.0") }""")
                }
            },
        )
        File(project, "src/main/AndroidManifest.xml").writeText(
            if (withAndroidX) {
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="@string/app_name"
                                 android:theme="@style/Theme.AppCompat.Light">
                        <activity android:name=".MainActivity" android:exported="true" />
                    </application>
                </manifest>
                """.trimIndent()
            } else {
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="agpdemo" />
                </manifest>
                """.trimIndent()
            },
        )
        File(project, "src/main/java/demo/app/Hello.java").writeText(
            "package demo.app;\npublic class Hello { public static String greet() { return \"$MARKER\"; } }\n",
        )
        if (withAndroidX) {
            File(project, "src/main/res/values").mkdirs()
            File(project, "src/main/res/values/strings.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">AGP on device</string>
                </resources>
                """.trimIndent(),
            )
            // Extends a class from the AAR, so the compile classpath has to
            // include what was resolved -- not just the platform.
            File(project, "src/main/java/demo/app/MainActivity.java").writeText(
                """
                package demo.app;

                import android.os.Bundle;
                import androidx.appcompat.app.AppCompatActivity;

                public class MainActivity extends AppCompatActivity {
                    @Override protected void onCreate(Bundle state) {
                        super.onCreate(state);
                        setTitle(getString(R.string.app_name) + " " + Hello.greet());
                    }
                }
                """.trimIndent(),
            )
        }
        File(project, "local.properties").writeText("sdk.dir=${sdk.absolutePath}\n")
        File(project, "gradle.properties").writeText(
            "android.aapt2FromMavenOverride=${aapt2().absolutePath}\n" +
                "org.gradle.jvmargs=-Xmx1g\n" +
                // AndroidX requires this, and AGP refuses the build without it.
                "android.useAndroidX=true\n",
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
        writeProject()
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

    /**
     * **A project that looks like a real one.**
     *
     * The test above builds something with no dependencies, no resources and
     * no library manifests — which exercises the toolchain but not the parts of
     * AGP that most builds spend their time in. This one adds an AndroidX
     * dependency, so the build has to:
     *
     *  - resolve `androidx.appcompat` and its transitive graph from Google's
     *    Maven and unpack the AARs,
     *  - merge those libraries' manifests into the application's,
     *  - link their resources together with the project's own, which is aapt2
     *    doing the work it exists for rather than compiling one file,
     *  - and compile against the AAR classes, since `MainActivity` extends a
     *    class that only exists there.
     *
     * `resources.arsc` is the assertion that matters: it is aapt2's output and
     * cannot appear unless linking really happened. `R.string.app_name`
     * resolving at compile time is the other half — the generated `R` class had
     * to be produced and put on the compile classpath.
     */
    @Test
    fun agp_builds_a_project_with_androidx_and_resources() {
        writeProject(withAndroidX = true)

        val run = gradle("assembleDebug")

        Log.i(TAG, "androidx build in ${run.millis} ms: exit=${run.exit}")
        run.output.takeLast(1800).chunked(900).forEach { Log.i(TAG, it) }
        assertTrue("the build failed:\n${run.output.takeLast(1500)}", run.exit == 0)

        val apk = File(project, "build/outputs/apk/debug/agpdemo-debug.apk")
        assertTrue("no APK was produced", apk.isFile)

        val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(
            "no resources.arsc, so aapt2 linked nothing: ${entries.take(20)}",
            "resources.arsc" in entries,
        )
        assertTrue(
            "no dex: ${entries.take(20)}",
            entries.any { it.startsWith("classes") && it.endsWith(".dex") },
        )
        // AppCompat ships its own resources; if merging worked they are here.
        assertTrue(
            "no library resources were merged, so only the project's own were " +
                "linked: ${entries.filter { it.startsWith("res/") }.take(10)}",
            entries.any { it.startsWith("res/") },
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
