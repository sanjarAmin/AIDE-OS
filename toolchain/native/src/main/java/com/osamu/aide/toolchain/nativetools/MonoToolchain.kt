package com.osamu.aide.toolchain.nativetools

import java.io.File

/**
 * A downloaded Mono, made usable.
 *
 * C# compiles and runs on a device, but only through [LinkerLaunch], and that
 * launch breaks one thing so thoroughly that the failure names something else
 * entirely. This class is where it is handled. Spike R14 measured it;
 * `tools/mono/FINDINGS.md` is the evidence.
 *
 * **Managed IO is bound to a path fixed when the package was built.** Mono's
 * own `etc/mono/config` maps `System.Native` to
 * `${'$'}mono_libdir/libmono-native.so`, and mono expands that variable to the
 * libdir it was configured with -- `/data/data/com.termux/files/usr/lib`, which
 * this app neither has nor can create. `Interop.Sys`'s static constructor
 * therefore throws `DllNotFoundException` and **every** `System.IO` call fails.
 *
 * What makes that worth encoding rather than documenting is how it presents:
 * `mcs` reports every unreadable file as
 *
 * ```
 * error CS2001: Source file `Hello.cs' could not be found
 * ```
 *
 * so a missing shared library appears as a missing *source* file, pointing at
 * the user's code. [configFor] rewrites the variable, which fixes all three
 * entries that use it at once.
 *
 * Two smaller things, both of which would otherwise be rediscovered:
 * `bin/mono` is a **symlink** to `mono-sgen`, so the linker is handed the
 * target; and `bin/mcs` is a **shell script** hardcoding Termux's prefix, so
 * the compiler is run as the assembly it is.
 */
class MonoToolchain(
    private val root: File,
    private val launch: LinkerLaunch,
) {

    /** The real runtime. `bin/mono` is a symlink and the linker wants the file. */
    val runtime: File get() = File(root, "bin/mono-sgen")

    /** The C# compiler, which is an assembly rather than a program. */
    val compiler: File get() = File(root, "lib/mono/4.5/mcs.exe")

    private val assemblies: File get() = File(root, "lib/mono/4.5")

    val isInstalled: Boolean
        get() = runtime.canRead() && compiler.isFile && launch.isAvailable

    /**
     * Mono's config with its build-time libdir rewritten, written into [into].
     *
     * Generated once and reused: it is a copy of the shipped file with one
     * substitution, and regenerating it per invocation would be work for
     * nothing. Returns null when the archive ships no config, which is not a
     * shape this has seen but would otherwise fail as a missing file much later.
     */
    fun configFor(into: File): File? {
        val shipped = File(root, "etc/mono/config").takeIf { it.isFile } ?: return null
        val generated = File(into, "mono-config")
        if (!generated.isFile) {
            into.mkdirs()
            generated.writeText(
                shipped.readText().replace(MONO_LIBDIR, File(root, "lib").absolutePath),
            )
        }
        return generated
    }

    /**
     * Runs the runtime with [arguments].
     *
     * [workspace] is where the rewritten config is kept; it has to be somewhere
     * the app may write, which the toolchain's own directory may not be once it
     * is installed read-only.
     */
    fun plan(
        arguments: List<String>,
        workspace: File,
        environment: Map<String, String> = emptyMap(),
    ): LaunchPlan = launch.plan(
        executable = runtime,
        arguments = arguments,
        libraryPath = listOf(File(root, "lib")),
        environment = buildMap {
            // Where the class library is, since the runtime looks for it under
            // the prefix it was built with.
            put("MONO_PATH", assemblies.absolutePath)
            configFor(workspace)?.let { put("MONO_CONFIG", it.absolutePath) }
            putAll(environment)
        },
    )

    /** Compiles [sources] into [output]. */
    fun planCompile(
        sources: List<File>,
        output: File,
        workspace: File,
        options: List<String> = emptyList(),
    ): LaunchPlan = plan(
        arguments = listOf(compiler.absolutePath) +
            options +
            sources.map { it.absolutePath } +
            "-out:${output.absolutePath}",
        workspace = workspace,
    )

    /** Runs a compiled assembly. */
    fun planRun(
        assembly: File,
        workspace: File,
        arguments: List<String> = emptyList(),
    ): LaunchPlan = plan(listOf(assembly.absolutePath) + arguments, workspace)

    private companion object {
        /** The variable mono expands to the libdir it was configured with. */
        const val MONO_LIBDIR = "\$mono_libdir"
    }
}
