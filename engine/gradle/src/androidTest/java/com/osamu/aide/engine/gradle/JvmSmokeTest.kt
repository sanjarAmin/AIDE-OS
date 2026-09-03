package com.osamu.aide.engine.gradle

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.toolchain.nativetools.JvmToolchain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostics for the layer under [GradleBuildSystemTest].
 *
 * When a Gradle build fails with nothing but an exit code, the question is
 * always which of three things broke: the launcher, the JVM, or the classpath
 * it was given. These separate them, and each prints what it saw — an exit code
 * with no transcript is what made the failure above unreadable in the first
 * place.
 */
@RunWith(AndroidJUnit4::class)
class JvmSmokeTest {

    private lateinit var context: Context
    private lateinit var jvm: JvmToolchain
    private lateinit var gradleHome: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val javaHome = File(context.filesDir, "jvm/lib/jvm/java-21-openjdk")
        assumeTrue("no JDK staged", File(javaHome, "lib/server/libjvm.so").isFile)
        jvm = JvmToolchain.from(context, javaHome, DefaultDispatcherProvider())
        assertTrue("preparing the JDK failed", jvm.prepare() is AppResult.Success)
        gradleHome = File(context.filesDir, "gradle").listFiles()
            ?.first { it.isDirectory && it.name.startsWith("gradle-") }!!
    }

    private fun run(mainClass: String, classPath: List<File>, args: List<String>): String {
        val out = StringBuilder()
        val result = runBlocking {
            jvm.run(
                mainClass = mainClass,
                classPath = classPath,
                vmOptions = listOf("-Djava.io.tmpdir=${File(context.cacheDir, "tmp").apply { mkdirs() }}"),
                arguments = args,
                workingDir = context.filesDir,
            ) { out.appendLine(it.text) }
        }
        Log.i(TAG, "$mainClass -> $result")
        out.lines().forEach { if (it.isNotBlank()) Log.i(TAG, "| $it") }
        return out.toString()
    }

    /** Where the prefix libraries are, and whether prepare() moved them. */
    @Test
    fun the_prefix_libraries_are_adopted() {
        val javaHome = File(context.filesDir, "jvm/lib/jvm/java-21-openjdk")
        val prefixLib = File(javaHome, "../..").canonicalFile
        Log.i(TAG, "javaHome=$javaHome")
        Log.i(TAG, "prefixLib=$prefixLib isDir=${prefixLib.isDirectory}")
        for (name in listOf("libz.so.1", "libandroid-shmem.so")) {
            val source = File(prefixLib, name)
            val real = runCatching { source.canonicalFile }.getOrDefault(source)
            val target = File(javaHome, "lib/$name")
            Log.i(
                TAG,
                "$name source=${source.exists()} real=$real isFile=${real.isFile} " +
                    "target=${target.exists()}",
            )
            if (!target.exists() && real.isFile) {
                val outcome = runCatching { real.copyTo(target, overwrite = true) }
                Log.i(TAG, "$name copy -> $outcome")
            }
        }
    }

    /** Does the JVM start at all, independent of Gradle? */
    @Test
    fun the_jvm_runs_a_jdk_class() {
        val out = run("java.lang.System", emptyList(), emptyList())
        Log.i(TAG, "system-class output: $out")
    }

    /** What is actually on the launcher classpath, and does GradleMain resolve? */
    @Test
    fun the_gradle_entry_point_is_reachable() {
        val lib = File(gradleHome, "lib")
        val launcher = lib.listFiles()!!.first {
            it.name.startsWith("gradle-launcher-") && it.extension == "jar"
        }
        Log.i(TAG, "launcher=$launcher exists=${launcher.isFile} readable=${launcher.canRead()}")

        val cli = lib.listFiles()!!.filter { it.name.startsWith("gradle-gradle-cli-main") }
        cli.forEach { Log.i(TAG, "cli-main=$it readable=${it.canRead()}") }

        val out = run("org.gradle.launcher.GradleMain", listOf(launcher) + cli, listOf("--version"))
        Log.i(TAG, "gradle --version output length=${out.length}")
    }
}

private const val TAG = "JvmSmokeTest"
