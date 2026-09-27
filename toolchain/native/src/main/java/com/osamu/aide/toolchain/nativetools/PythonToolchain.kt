package com.osamu.aide.toolchain.nativetools

import java.io.File

/**
 * A downloaded CPython, made usable.
 *
 * Python runs on a device through [LinkerLaunch], like every other downloaded
 * toolchain here, and **it is the best-behaved of them under that launch**.
 * Spike R17 measured it; `tools/python/FINDINGS.md` is the evidence.
 *
 *  1. **Its libraries are not where it was built to look.** The binary's
 *     `RUNPATH` is `/data/data/com.termux/files/usr/lib`, a prefix this app
 *     neither has nor can create, so without `LD_LIBRARY_PATH` the launch dies
 *     with `CANNOT LINK EXECUTABLE ... library "libandroid-support.so" not
 *     found` before a line of Python runs. The same cure as node, clang and the
 *     JDK.
 *
 *  2. **It knows where it is, which node does not.** This is the difference
 *     that shapes this class. Node reads `/proc/self/exe` to find itself and
 *     therefore believes it is `/system/bin/linker64`; CPython derives
 *     `sys.prefix` from `argv[0]`, which under this launch is the real binary.
 *     So `sys.prefix`, `sys.executable` and the standard library path are all
 *     correct with nothing done about them -- **no `PYTHONHOME` is set here,
 *     and setting one would be worse than useless**: it would hardcode a path
 *     that changes when the component is reinstalled.
 *
 * **`bin/python3` is a symlink** to the versioned binary, so the archive has to
 * move as a tar -- `adb push` of a tree drops every symlink
 * (`tools/clang/FINDINGS.md` §4). [runtime] resolves the version for itself
 * rather than naming one, because the version in the archive is Termux's choice
 * and changes without this code changing.
 */
class PythonToolchain(
    private val root: File,
    private val launch: LinkerLaunch,
) {

    /**
     * The interpreter, which is what a spawned child must be given.
     *
     * The **versioned** binary and not `bin/python3`, even though both work:
     * the symlink is one more thing that can be missing from an interrupted
     * unpack, and a link resolving nowhere fails at `execve` with a message
     * about the link rather than about the install.
     */
    val runtime: File
        get() = versionedRuntime ?: File(root, "bin/python3")

    /**
     * `bin/python3.14`, or whatever version this archive carries.
     *
     * Found by listing rather than pinned, and cached because a run plans
     * several commands and this touches the filesystem. Null when the install
     * is absent or incomplete, which is what makes [isInstalled] honest.
     */
    private val versionedRuntime: File? by lazy {
        File(root, "bin").listFiles()
            ?.filter { it.isFile && VERSIONED_RUNTIME.matches(it.name) }
            // Sorted so a tree that somehow holds two is resolved the same way
            // every time rather than in directory order.
            ?.minByOrNull { it.name }
    }

    val isInstalled: Boolean get() = versionedRuntime?.canRead() == true && launch.isAvailable

    /**
     * Runs the interpreter with [arguments].
     *
     * [environment] is merged over the defaults rather than replacing them, so
     * a caller can add to a project's environment without having to know that
     * `LD_LIBRARY_PATH` is load-bearing.
     */
    fun plan(
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): LaunchPlan = launch.plan(
        executable = runtime,
        arguments = arguments,
        libraryPath = listOf(File(root, "lib")),
        environment = environment,
    )

    /** Runs a script, which is what a project is. */
    fun planScript(
        script: File,
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
    ): LaunchPlan = plan(listOf(script.absolutePath) + arguments, environment)

    /**
     * Runs pip.
     *
     * `-m pip` and not `bin/pip`, which is a `#!` script whose shebang names
     * Termux's prefix -- a path this app does not have, so the kernel refuses
     * it with `ENOENT` naming the interpreter and not the script. The module
     * is the same pip and needs no such path. This is the same shape as npm
     * having to be run as `npm-cli.js`, arrived at for a different reason.
     *
     * Null when the archive has no pip, rather than a plan that fails with
     * `No module named pip` -- which names a module and not the install.
     */
    fun planPip(
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): LaunchPlan? =
        if (!hasPip) null else plan(listOf("-m", "pip") + arguments, environment)

    /** True when this archive carries pip as well as the interpreter. */
    val hasPip: Boolean
        get() = versionedRuntime?.let { runtime ->
            val version = runtime.name.removePrefix("python")
            File(root, "lib/python$version/site-packages/pip").isDirectory
        } == true

    /**
     * The environment a project run needs, given the app's own directories.
     *
     * Kept separate from [plan] because only the caller knows them, and passing
     * a `Context` into a toolchain class would put Android in a layer that has
     * none.
     *
     * `PYTHONDONTWRITEBYTECODE` is the one entry here that is a decision rather
     * than plumbing. Without it the first run of a project litters every source
     * directory with `__pycache__`, which the file tree hides and git does not,
     * and the saving it buys is milliseconds on a script small enough to be
     * edited on a phone. `PYTHONUNBUFFERED` matters more than it looks: output
     * is read through a pipe, which makes stdout block-buffered, so a long run
     * would print nothing until it ended.
     *
     * [packages] is where `pip install --target` puts a project's dependencies,
     * and it is on `PYTHONPATH` rather than being a virtualenv -- see
     * `PythonRunSystem.pip`. It is named even when it does not exist yet:
     * Python ignores a `PYTHONPATH` entry that is not a directory, and making
     * it conditional would mean the first install needed a second run before
     * anything could import.
     */
    fun prepareEnvironment(home: File, cache: File, packages: File): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "TMPDIR" to cache.absolutePath,
        "PYTHONDONTWRITEBYTECODE" to "1",
        "PYTHONUNBUFFERED" to "1",
        "PYTHONPATH" to packages.absolutePath,
        // pip defaults its cache to $HOME/.cache/pip; naming it explicitly
        // means a caller can point it somewhere it is willing to have cleared.
        "PIP_CACHE_DIR" to File(cache, "pip").absolutePath,
    )

    private companion object {
        /** `python3.14`, and not `python3.14-config`, which is a shell script. */
        val VERSIONED_RUNTIME = Regex("""python3\.\d+""")
    }
}
