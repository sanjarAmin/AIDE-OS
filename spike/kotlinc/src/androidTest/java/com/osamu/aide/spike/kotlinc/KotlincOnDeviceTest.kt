package com.osamu.aide.spike.kotlinc

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Spike R2: run the Kotlin compiler, and the Compose compiler plugin, on ART.
 *
 * The plan assumed the Compose plugin would have to be dexed and side-loaded
 * into a running compiler. Half of that turned out to be unnecessary and half
 * unavoidable. `kotlinc.jar` holds the compiler and the plugin together --
 * classes*.dex plus the resources they read back, laid out like an APK -- so
 * there is no second archive and no jar loading: the plugin's classes are
 * already loaded and linked before the compiler starts.
 *
 * What that does *not* do is register it. Plugin discovery deliberately avoids
 * the classloader: `ServiceLoaderLite` opens each plugin classpath file and
 * reads `META-INF/services` out of the zip itself. So the archive still has to
 * be named with `-Xplugin`, pointing the compiler at the file it is already
 * running from -- and it has to be named `.jar`, because that lookup matches on
 * the file extension and returns an empty list, without complaint, for anything
 * else. The archive is a zip whatever it is called; only the name decides
 * whether the plugin is found.
 */
@RunWith(AndroidJUnit4::class)
class KotlincOnDeviceTest {

    private lateinit var context: Context
    private lateinit var workDir: File
    private lateinit var compilerLoader: ClassLoader
    private lateinit var stdlib: File
    private lateinit var composeRuntime: File
    private lateinit var kotlinHome: File
    private lateinit var androidJar: File
    private lateinit var archive: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        workDir = File(context.cacheDir, "kotlinc-spike").apply {
            deleteRecursively()
            mkdirs()
        }

        archive = stageAsset("kotlinc.jar")
        composeRuntime = stageAsset("compose-runtime.jar")

        // ART is not a JDK: there are no class roots under /apex/com.android.art
        // for the compiler to read. Android code is compiled against android.jar
        // with -no-jdk regardless, so this is the normal configuration, not a
        // workaround.
        androidJar = stageAsset("android.jar")

        // Without -kotlin-home the compiler locates its own installation by
        // asking the classloader for PathUtil.class *as a resource* -- which
        // does not exist in a dex, where there are no .class entries at all.
        // Staging a home directory sidesteps that lookup entirely.
        kotlinHome = File(workDir, "kotlin-home").apply { mkdirs() }
        val lib = File(kotlinHome, "lib").apply { mkdirs() }
        stdlib = stageAsset("kotlin-stdlib.jar", into = lib)

        // Parent must be the boot classloader, not the app's.
        //
        // The archive carries its own kotlin-stdlib, and so does the app. D8
        // synthesises helper classes (AtomicIntrinsicsKt$$ExternalSynthetic*)
        // independently for each, with different generated method names. With
        // the app loader as parent, parent-first delegation hands the archive
        // the app's synthetics and linking fails with NoSuchMethodError deep
        // inside IntelliJ's message bus. Isolating the loader keeps the
        // compiler's stdlib and its synthetics consistent with each other.
        val bootLoader: ClassLoader? = Any::class.java.classLoader
        compilerLoader = PathClassLoader(archive.absolutePath, bootLoader)
    }

    private fun stageAsset(name: String, into: File = workDir): File {
        val target = File(into, name)
        context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        // Since API 29 the platform refuses to load a dex file the app can still
        // write to -- the same W^X reasoning that governs executing binaries.
        // Without this, PathClassLoader throws SecurityException.
        target.setReadOnly()
        return target
    }

    /**
     * Invokes the compiler CLI entry point reflectively, since the compiler
     * lives in a classloader this test cannot link against at build time.
     */
    private fun compile(sourceFile: File, outDir: File): Pair<String, String> {
        val compilerClass = compilerLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compiler = compilerClass.getDeclaredConstructor().newInstance()
        val exec = compilerClass.getMethod(
            "exec",
            PrintStream::class.java,
            Array<String>::class.java,
        )

        val args = arrayOf(
            "-no-stdlib",
            "-no-reflect",
            "-no-jdk",
            "-kotlin-home", kotlinHome.absolutePath,
            // Dexing the plugin into the compiler's own archive is not enough to
            // register it. Plugin discovery does not go through the classloader:
            // ServiceLoaderLite opens each plugin classpath *file* and reads
            // META-INF/services out of the zip, so a plugin the compiler can
            // already load is still invisible until its file is named here.
            // Naming the archive works because it is that file -- the descriptor
            // is read from the zip, and the class it names then resolves through
            // the classloader that is already holding the dex.
            "-Xplugin=${archive.absolutePath}",
            "-classpath",
            listOf(androidJar, stdlib, composeRuntime)
                .joinToString(File.pathSeparator) { it.absolutePath },
            "-d", outDir.absolutePath,
            "-jvm-target", "11",
            "-module-name", "spike",
            sourceFile.absolutePath,
        )

        val diagnostics = ByteArrayOutputStream()
        // Plugin discovery runs through the thread context classloader; without
        // this the compiler cannot see its own extensions, let alone Compose.
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = compilerLoader
        val exitCode = try {
            PrintStream(diagnostics, true).use { exec.invoke(compiler, it, args) }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }

        return exitCode.toString() to diagnostics.toString()
    }

    /** Does the compiler run on ART at all? */
    @Test
    fun compiles_plain_kotlin() {
        val src = File(workDir, "Plain.kt").apply {
            writeText(
                """
                package spike
                fun greet(name: String): String = "hello ${'$'}name"
                """.trimIndent(),
            )
        }
        val out = File(workDir, "out-plain").apply { mkdirs() }

        val (exitCode, diagnostics) = compile(src, out)

        assertEquals("compiler said: $diagnostics", "OK", exitCode)
        val classes = out.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue("no .class files produced. $diagnostics", classes.isNotEmpty())
    }

    /** The actual R2 question: does the Compose plugin load and transform code? */
    @Test
    fun compiles_and_transforms_a_composable() {
        val src = File(workDir, "Ui.kt").apply {
            writeText(
                """
                package spike

                import androidx.compose.runtime.Composable

                @Composable
                fun Greeting(name: String) {
                    Nested(name)
                }

                @Composable
                fun Nested(name: String) {
                }
                """.trimIndent(),
            )
        }
        val out = File(workDir, "out-compose").apply { mkdirs() }

        val (exitCode, diagnostics) = compile(src, out)

        assertEquals("compiler said: $diagnostics", "OK", exitCode)

        val classes = out.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue("no .class files produced. $diagnostics", classes.isNotEmpty())

        // The plugin rewrites every @Composable to take a Composer parameter and
        // wraps the body in group calls. If the plugin had silently not loaded,
        // the file would still compile -- just without any of this. Checking the
        // bytecode is the only way to tell the two apart.
        val bytecode = classes.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertTrue(
            "compiled output has no reference to Composer: the Compose plugin did not run. " +
                "compiler said: $diagnostics",
            bytecode.contains("androidx/compose/runtime/Composer"),
        )
        assertTrue(
            "no composable group calls emitted: the plugin loaded but did not transform. " +
                "compiler said: $diagnostics",
            bytecode.contains("startRestartGroup") || bytecode.contains("startReplaceGroup"),
        )
    }
}
