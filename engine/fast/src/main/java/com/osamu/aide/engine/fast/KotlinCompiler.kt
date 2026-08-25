package com.osamu.aide.engine.fast

import dalvik.system.PathClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipFile

/**
 * The Kotlin compiler, loaded once and kept.
 *
 * Kept because startup dominates everything else: spike R2 measured ~11 s for a
 * one-file compile, nearly all of it building the application environment,
 * registering extension points and reading builtins. That cost is paid per
 * `PathClassLoader`, so one is created for the life of the process and every
 * build reuses it. Constructing this per build would make Kotlin projects
 * unusable and would look like the compiler being slow.
 *
 * Every non-obvious line here is load-bearing and comes from
 * `tools/kotlinc/FINDINGS.md`. Read it before changing any of them: several
 * failures in that document produce no error at all, only a clean compile that
 * quietly did the wrong thing.
 *
 * Public because it has to outlive a build. `FastBuildSystem` is constructed
 * per build; this must not be, so the app holds one and passes it in.
 */
class KotlinCompiler(private val toolchain: KotlinToolchain, cacheDir: File) {

    /**
     * A `kotlin-home` for the compiler to find instead of looking for itself.
     *
     * Without `-kotlin-home` the compiler locates its own installation by asking
     * the classloader for `PathUtil.class` **as a resource**. A dex has no
     * `.class` entries, so that lookup fails and startup dies. Staging a
     * directory sidesteps the question rather than answering it.
     */
    private val kotlinHome: File = File(cacheDir, "kotlin-home").apply {
        File(this, "lib").mkdirs()
    }

    private val stagedStdlib: File = File(kotlinHome, "lib/${toolchain.stdlib.name}")

    /**
     * Parented to the **boot** loader, not the app's.
     *
     * The archive carries its own kotlin-stdlib and so does the app, and D8
     * synthesises helper classes independently for each with different
     * generated names. Under parent-first delegation from the app's loader the
     * archive is handed the app's synthetics and linking dies with
     * `NoSuchMethodError` deep inside IntelliJ's message bus. Isolating it keeps
     * the compiler's stdlib and its synthetics consistent with each other.
     */
    private val loader: ClassLoader by lazy {
        if (!stagedStdlib.isFile) toolchain.stdlib.copyTo(stagedStdlib, overwrite = true)
        PathClassLoader(toolchain.archive.absolutePath, Any::class.java.classLoader)
    }

    /** What the compiler said, and whether it succeeded. */
    data class Outcome(val succeeded: Boolean, val output: String)

    internal fun compile(
        sources: List<File>,
        classpath: List<File>,
        outputDir: File,
        moduleName: String,
    ): Outcome {
        val compilerClass = loader.loadClass(K2JVM_COMPILER)
        val compiler = compilerClass.getDeclaredConstructor().newInstance()
        val exec = compilerClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)

        val args = buildList {
            // The archive already carries these; adding them again puts two
            // copies of every stdlib class on the classpath.
            add("-no-stdlib")
            add("-no-reflect")

            // ART is not a JDK -- there are no class roots to read. Android code
            // is compiled against android.jar with -no-jdk regardless, so this
            // is the ordinary configuration rather than a workaround.
            add("-no-jdk")

            add("-kotlin-home"); add(kotlinHome.absolutePath)

            // Registered only for a project that actually uses Compose.
            //
            // Dexing the plugin beside the compiler links it but does not
            // register it: discovery deliberately avoids the classloader and
            // reads META-INF/services out of each plugin *file*, so naming the
            // archive here points the compiler at the file it is already
            // running from.
            //
            // But registering it is not free. The plugin checks for the Compose
            // runtime the moment it is asked to generate IR and throws
            // IncompatibleComposeRuntimeVersionException when it is absent --
            // so passing this unconditionally makes every plain Kotlin project
            // fail to build with an error about Compose it never mentioned.
            if (usesCompose(classpath)) add("-Xplugin=${toolchain.archive.absolutePath}")

            add("-classpath")
            add((classpath + stagedStdlib).joinToString(File.pathSeparator) { it.absolutePath })

            add("-d"); add(outputDir.absolutePath)
            add("-jvm-target"); add(JVM_TARGET)
            add("-module-name"); add(moduleName)

            addAll(sources.map { it.absolutePath })
        }

        val diagnostics = ByteArrayOutputStream()
        // Plugin discovery runs through the thread context classloader. Without
        // this the compiler cannot see its own extensions, let alone Compose.
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        val exitCode = try {
            PrintStream(diagnostics, true).use { exec.invoke(compiler, it, args.toTypedArray()) }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }

        return Outcome(exitCode.toString() == "OK", diagnostics.toString())
    }

    /**
     * Whether the Compose runtime is on the classpath.
     *
     * By marker class rather than by artifact name: the runtime can arrive as a
     * jar, as an AAR's extracted `classes.jar`, or under a coordinate this code
     * has never heard of, and matching filenames would miss all but the first.
     * Reading zip directories is cheap next to a compile, and the answer is
     * cached because a project's classpath does not change between builds.
     */
    private fun usesCompose(classpath: List<File>): Boolean =
        composeByClasspath.getOrPut(classpath.map { it.absolutePath }) {
            classpath.any { entry ->
                entry.isFile && runCatching {
                    ZipFile(entry).use { it.getEntry(COMPOSE_MARKER) != null }
                }.getOrDefault(false)
            }
        }

    private val composeByClasspath = mutableMapOf<List<String>, Boolean>()

    private companion object {
        const val K2JVM_COMPILER = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        /** Present in every version of the Compose runtime worth compiling against. */
        const val COMPOSE_MARKER = "androidx/compose/runtime/Composer.class"

        /** Matches [JavaCompileStage]'s level; the two halves share a classpath. */
        const val JVM_TARGET = "11"
    }
}
