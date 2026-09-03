package com.osamu.aide.engine.gradle

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.awaitResult
import com.osamu.aide.toolchain.nativetools.JvmToolchain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `:engine:gradle` building a real Android project, on the device.
 *
 * The acceptance test for M9's engine half. Spike R11 proved the mechanism by
 * driving Gradle directly; this drives it through [com.osamu.aide.engine.api.BuildSystem],
 * which is what the app holds — so a break in the engine's own plumbing shows
 * up here rather than in a spike that bypasses it.
 *
 * The JDK, Gradle and an Android SDK are staged out of band; every one is a
 * large download and none of them belongs in git. See `tools/rootfs/`.
 */
@RunWith(AndroidJUnit4::class)
class GradleBuildSystemTest {

    private lateinit var context: Context
    private lateinit var engine: GradleBuildSystem
    private lateinit var project: Project
    private lateinit var support: File
    private lateinit var androidSdk: AndroidSdk

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatchers = DefaultDispatcherProvider()

        unpack("jvm.tar", File(context.filesDir, "jvm"), "lib/jvm/java-21-openjdk/lib/server/libjvm.so")
        unpack("gradle.tar", File(context.filesDir, "gradle"), null)
        unpack("sdk36.tar", File(context.filesDir, "sdk"), null)
        // build-tools and an accepted licence, which AGP refuses to build
        // without. Unpacked into the SDK rather than beside it.
        unpack("sdk-extra.tar", File(context.filesDir, "sdk/sdk36"), "licenses")

        val javaHome = File(context.filesDir, "jvm/lib/jvm/java-21-openjdk")
        val gradleHome = File(context.filesDir, "gradle").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("gradle-") }
        val sdk = File(context.filesDir, "sdk/sdk36")

        assumeTrue("no JDK staged", File(javaHome, "lib/server/libjvm.so").isFile)
        assumeTrue("no Gradle staged", gradleHome != null)
        assumeTrue("no Android SDK staged", File(sdk, "platforms/android-36/android.jar").isFile)

        val jvm = JvmToolchain.from(context, javaHome, dispatchers)
        assertTrue("preparing the JDK failed", jvm.prepare() is AppResult.Success)

        support = File(context.filesDir, "gradle-home").apply { mkdirs() }
        androidSdk = AndroidSdk(
            dir = sdk,
            bundledAapt2 = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so"),
            linkDir = File(support, "bin"),
        )
        engine = GradleBuildSystem(jvm, gradleHome!!, dispatchers, support, androidSdk)
        assertTrue("the engine reports itself uninstalled", engine.isInstalled)

        project = writeProject()
    }

    /** Unpacked by this process; see `tools/clang/FINDINGS.md` §4. */
    private fun unpack(name: String, into: File, marker: String?) {
        if (marker != null && File(into, marker).exists()) return
        if (marker == null && into.isDirectory && into.list()?.isNotEmpty() == true) return
        val archive = File(context.getExternalFilesDir(null), name)
        if (!archive.isFile) return
        into.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", into.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun writeProject(): Project {
        val root = File(context.filesDir, "gradle-project").apply { deleteRecursively(); mkdirs() }
        File(root, "src/main/java/demo/app").mkdirs()
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "gradledemo"
            """.trimIndent(),
        )
        File(root, "build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") version "9.3.2" }
            android {
                namespace = "demo.app"
                compileSdk { version = release(36) }
                defaultConfig { applicationId = "demo.app"; minSdk = 26; versionCode = 1 }
            }
            """.trimIndent(),
        )
        File(root, "src/main/AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="gradledemo" />
            </manifest>
            """.trimIndent(),
        )
        File(root, "src/main/java/demo/app/Hello.java").writeText(
            "package demo.app;\npublic class Hello { public static String greet() { return \"gradle\"; } }\n",
        )
        // No local.properties and no aapt2 override written here any more:
        // both are the engine's job now, which is the point of this fixture
        // looking like an ordinary project rather than a staged one.
        //
        // The heap size stays, and stays here: what a Gradle build may use on a
        // phone is R3's question, and inventing an answer inside the engine
        // would settle it by accident.
        File(root, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g\n")

        return Project(
            name = "gradledemo",
            rootDir = root,
            applicationId = "demo.app",
            language = SourceLanguage.JAVA,
            engine = BuildEngine.GRADLE,
            lastOpenedAt = 0L,
        )
    }

    /**
     * **The acceptance question.** An Android project, built by the project's
     * own Gradle, through the interface the app holds.
     *
     * The APK is handed to the platform's package parser rather than merely
     * checked for entries: that is the difference between a zip and something
     * Android would install.
     */
    @Test
    fun it_builds_an_android_project() {
        val events = runBlocking {
            engine.build(BuildRequest(project = project, outputDir = File(support, "out"))).toList()
        }

        val result = events.filterIsInstance<BuildEvent.Finished>().single().result
        Log.i(TAG, "result=$result")
        assertTrue("the build failed: $result", result is BuildResult.Success)

        val apk = (result as BuildResult.Success).apk
        assertTrue("the APK does not exist", apk.isFile)
        val info = context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
        assertTrue("the platform's package parser rejected it", info != null)
        assertEquals("demo.app", info!!.packageName)
        Log.i(TAG, "APK is ${apk.length()} bytes in ${result.durationMillis} ms")
    }

    /**
     * Progress reaches the caller as it happens.
     *
     * A build that reported nothing until it finished would read as a hang, and
     * on a phone this takes a minute. The stages come from Gradle's task lines;
     * the mapping is [GradleOutput]'s.
     */
    @Test
    fun it_reports_stages_while_it_runs() {
        val events = runBlocking {
            engine.build(BuildRequest(project = project, outputDir = File(support, "out"))).toList()
        }

        val started = events.filterIsInstance<BuildEvent.StageStarted>().map { it.stage }
        Log.i(TAG, "stages: $started")
        assertTrue("no stages were reported at all", started.isNotEmpty())
        // A stage reported twice is a progress bar going backwards. Several
        // Gradle tasks map onto each kind of work, so this is the assertion
        // that keeps the mapping honest.
        assertEquals("a stage was reported more than once: $started", started.distinct(), started)
        assertTrue("resources were never linked: $started", BuildStage.LINK_RESOURCES in started)
        assertTrue("nothing was dexed: $started", BuildStage.DEX in started)
        // Every stage that opened has to close, or a progress bar never empties.
        assertEquals(
            "a stage started and never completed",
            started,
            events.filterIsInstance<BuildEvent.StageCompleted>().map { it.stage },
        )
        assertTrue("Finished was not last", events.last() is BuildEvent.Finished)
    }

    /**
     * **A project with more than one module**, which is what a real one is.
     *
     * M9's acceptance test says "an unmodified Android Studio project builds",
     * and until now everything built here has been a single module — a shape
     * Android Studio does not produce and almost nobody keeps. Two things only
     * appear at two modules: an application that depends on a library, and an
     * APK that is not under the root project's own build directory.
     *
     * That second one is the reason this is worth a test rather than an
     * assumption. `findApk` searches rather than computes, and a search that
     * started at the wrong root, or took the newest file across modules, would
     * pass every single-module test and hand back the library's output here.
     */
    @Test
    fun it_builds_a_project_with_a_library_module() {
        val root = writeMultiModuleProject()
        val multi = project.copy(name = "multidemo", rootDir = root, applicationId = "demo.multi")

        val result = runBlocking {
            engine.build(BuildRequest(project = multi, outputDir = File(support, "out-multi")))
                .awaitResult()
        }
        Log.i(TAG, "multi-module result=$result")
        assertTrue("the build failed: $result", result is BuildResult.Success)

        val apk = (result as BuildResult.Success).apk
        // The application module's APK, not the library's -- a library produces
        // no APK at all, so taking "the newest output" carelessly across a
        // multi-module tree is how this would go wrong.
        assertTrue("the APK did not come from the application module: $apk", "/app/" in apk.path)

        val info = context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
        assertTrue("the platform's package parser rejected it", info != null)
        assertEquals("demo.multi", info!!.packageName)
        Log.i(TAG, "multi-module APK is ${apk.length()} bytes in ${result.durationMillis} ms")
    }

    /** An application module and a library module it actually depends on. */
    private fun writeMultiModuleProject(): File {
        val root = File(context.filesDir, "gradle-multi").apply { deleteRecursively(); mkdirs() }
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "multidemo"
            include(":app")
            include(":lib")
            """.trimIndent(),
        )
        File(root, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "9.3.2" apply false
                id("com.android.library") version "9.3.2" apply false
            }
            """.trimIndent(),
        )

        File(root, "lib/src/main/java/demo/lib").mkdirs()
        File(root, "lib/build.gradle.kts").writeText(
            """
            plugins { id("com.android.library") }
            android {
                namespace = "demo.lib"
                compileSdk { version = release(36) }
                defaultConfig { minSdk = 26 }
            }
            """.trimIndent(),
        )
        File(root, "lib/src/main/AndroidManifest.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?><manifest />""",
        )
        File(root, "lib/src/main/java/demo/lib/Greeting.java").writeText(
            "package demo.lib;\npublic class Greeting { public static String text() { return \"lib\"; } }\n",
        )

        File(root, "app/src/main/java/demo/multi").mkdirs()
        File(root, "app/build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") }
            android {
                namespace = "demo.multi"
                compileSdk { version = release(36) }
                defaultConfig { applicationId = "demo.multi"; minSdk = 26; versionCode = 1 }
            }
            dependencies { implementation(project(":lib")) }
            """.trimIndent(),
        )
        File(root, "app/src/main/AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="multidemo" />
            </manifest>
            """.trimIndent(),
        )
        // Calls into the library, so a build that did not actually wire the
        // modules together fails to compile rather than quietly producing an
        // APK with nothing of the library in it.
        File(root, "app/src/main/java/demo/multi/Hello.java").writeText(
            "package demo.multi;\nimport demo.lib.Greeting;\n" +
                "public class Hello { public static String greet() { return Greeting.text(); } }\n",
        )
        File(root, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g\n")
        return root
    }

    /**
     * A project Gradle cannot build is refused before anything starts, with a
     * sentence rather than an exit code.
     */
    @Test
    fun a_project_without_settings_is_refused_by_name() {
        File(project.rootDir, "settings.gradle.kts").delete()

        val result = runBlocking {
            engine.build(BuildRequest(project = project, outputDir = File(support, "out"))).awaitResult()
        }

        assertTrue(result is BuildResult.Failure)
        assertTrue(
            "the message does not say what is missing: $result",
            (result as BuildResult.Failure).message.contains("settings.gradle"),
        )
    }

    private companion object {
        const val TAG = "GradleEngine"
    }
}
