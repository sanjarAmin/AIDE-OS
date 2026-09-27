package com.osamu.aide.toolchain.nativetools

import android.content.Context
import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import java.io.File
import java.nio.file.Files

/**
 * A downloaded JDK, made usable.
 *
 * A JVM runs on a device, but not the way its own launcher expects, and the
 * gap is entirely about which files may be executed. Everything here follows
 * from one rule: **an app may only execute what lives in `nativeLibraryDir`**
 * (spike R9), and a downloaded JDK does not live there.
 *
 *  1. **`bin/java` cannot start.** It re-execs itself through
 *     `/proc/self/exe`, which under the only launch route available is the
 *     dynamic linker, so the re-exec becomes `linker64 -version`. Replaced by
 *     [launcher], our own `JNI_CreateJavaVM` caller, which never re-execs.
 *  2. **A build execs JDK tools** — Gradle forks `java` for its daemon, AGP
 *     runs `jlink`. Each is redirected to the same launcher, which dispatches
 *     on the name it was invoked under.
 *  3. **The JVM cannot spawn anything.** Its default `POSIX_SPAWN` runs
 *     `jspawnhelper` and has *it* exec the target; the JDK's copy is in app
 *     storage. We ship one and point the JDK's at it.
 *
 * All three are the same fix: a symlink from where a tool is expected to a copy
 * the app may run, because the kernel checks the *resolved* file.
 * `tools/rootfs/FINDINGS.md`.
 */
class JvmToolchain(
    /** The JDK's home, e.g. `<install>/lib/jvm/java-21-openjdk`. */
    val javaHome: File,
    private val nativeLibraryDir: File,
    private val runner: NativeToolRunner,
) {

    /** Our launcher, in the one directory an app may execute from. */
    val launcher: File get() = File(nativeLibraryDir, LAUNCHER)

    /** The spawn helper we ship, for the same reason. */
    private val spawnHelper: File get() = File(nativeLibraryDir, SPAWN_HELPER)

    /**
     * Whether this JDK can be used at all.
     *
     * Checks what a build reaches for rather than that a directory exists: a
     * partial unpack of a 300 MB archive is a real failure mode and presents
     * as an error deep inside a compile.
     */
    val isInstalled: Boolean
        get() = File(javaHome, "lib/server/libjvm.so").isFile &&
            File(javaHome, "lib/modules").isFile &&
            launcher.canExecute()

    /**
     * Points the JDK's own binaries at ours.
     *
     * Idempotent, and it keeps each original beside the link as `<name>.real`
     * so the substitution is visible to anyone looking and can be undone.
     *
     * Called once after the JDK is installed. Doing it at install time rather
     * than per build matters: Gradle forks its daemon by exec'ing
     * `$java.home/bin/java` itself, so the path has to be right before any
     * build starts, not while one is running.
     */
    fun prepare(): AppResult<Unit> {
        if (!launcher.canExecute()) {
            return AppResult.Failure(
                AppError("The JVM launcher is missing from this build's native libraries."),
            )
        }
        return runCatching {
            REDIRECTED_TOOLS.forEach { tool -> redirect(File(javaHome, "bin/$tool"), launcher) }
            if (spawnHelper.canExecute()) {
                redirect(File(javaHome, "lib/jspawnhelper"), spawnHelper)
            }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError("Could not prepare the JDK: ${it.message}", it)) },
        )
    }

    /**
     * Replaces [target] with a link to [replacement], keeping the original.
     *
     * A missing target is skipped rather than treated as an error: JDKs differ
     * in which tools they ship, and demanding all of them would make a working
     * install look broken.
     */
    private fun redirect(target: File, replacement: File) {
        val kept = File(target.parentFile, "${target.name}.real")
        val isLink = Files.isSymbolicLink(target.toPath())
        if (!target.exists() && !isLink) return
        if (!kept.exists() && !isLink) {
            if (!target.renameTo(kept)) return
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), replacement.toPath())
    }

    /**
     * Runs a main class on this JVM.
     *
     * [vmOptions] reach the VM; [arguments] reach the program. They are
     * separate parameters rather than one list because `java` distinguishes
     * them by position and getting that wrong silently passes a VM flag to the
     * program.
     */
    suspend fun run(
        mainClass: String,
        classPath: List<File> = emptyList(),
        vmOptions: List<String> = emptyList(),
        arguments: List<String> = emptyList(),
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
        onLine: (ToolLine) -> Unit = {},
    ): AppResult<ToolResult> {
        if (!isInstalled) {
            return AppResult.Failure(
                AppError("The Java runtime is not installed, or its download did not finish."),
            )
        }
        val command = buildList {
            add(launcher.absolutePath)
            if (classPath.isNotEmpty()) {
                add("-cp")
                add(classPath.joinToString(File.pathSeparator) { it.absolutePath })
            }
            addAll(vmOptions)
            add(mainClass)
            addAll(arguments)
        }
        return runner.run(
            plan = LaunchPlan(command, defaultEnvironment() + environment),
            workingDir = workingDir,
            describedAs = "java",
            onLine = onLine,
        )
    }

    /**
     * Runs an executable jar, as `java -jar` does.
     *
     * The main class comes from the jar's own manifest. Worth having because it
     * is how a Gradle wrapper starts: `gradlew` is a shell script whose real
     * work is `java -jar gradle-wrapper.jar`, and a project that pins its
     * Gradle version expects to be built that way.
     */
    suspend fun runJar(
        jar: File,
        vmOptions: List<String> = emptyList(),
        arguments: List<String> = emptyList(),
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
        onLine: (ToolLine) -> Unit = {},
    ): AppResult<ToolResult> {
        if (!isInstalled) {
            return AppResult.Failure(
                AppError("The Java runtime is not installed, or its download did not finish."),
            )
        }
        return runner.run(
            plan = LaunchPlan(
                command = listOf(launcher.absolutePath) + vmOptions +
                    listOf("-jar", jar.absolutePath) + arguments,
                environment = defaultEnvironment() + environment,
            ),
            workingDir = workingDir,
            describedAs = "java -jar",
            onLine = onLine,
        )
    }

    /**
     * What the JVM needs to find its own libraries.
     *
     * The launcher sets this for itself when it has to, but passing it here
     * saves the restart that costs -- and anything the JVM spawns inherits it.
     */
    fun defaultEnvironment(): Map<String, String> = mapOf(
        LinkerLaunch.LD_LIBRARY_PATH to listOf(
            File(javaHome, "lib/server"),
            File(javaHome, "lib"),
            // The Termux prefix two levels up, which carries libz and the
            // shared-memory shim libjvm is linked against.
            File(javaHome, "../..").let { runCatching { it.canonicalFile }.getOrDefault(it) },
        ).joinToString(":") { it.absolutePath },
        "JAVA_HOME" to javaHome.absolutePath,
    )

    companion object {
        const val LAUNCHER = "libjvmlauncher.so"
        const val SPAWN_HELPER = "libjspawnhelper.so"

        /**
         * The JDK binaries a build is known to execute.
         *
         * `java` for Gradle's daemon and `jlink` for AGP's JdkImageTransform
         * are the two that have actually been hit; the rest are the same kind
         * of file and finding them out one failed build at a time is slower
         * than listing them.
         */
        val REDIRECTED_TOOLS = listOf("java", "jlink", "javac", "jar", "javadoc", "jdeps", "jmod")

        fun from(context: Context, javaHome: File, dispatchers: DispatcherProvider) = JvmToolchain(
            javaHome = javaHome,
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            runner = NativeToolRunner(NativeToolchain.from(context), dispatchers),
        )
    }
}
