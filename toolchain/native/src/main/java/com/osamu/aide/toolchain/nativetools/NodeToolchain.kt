package com.osamu.aide.toolchain.nativetools

import java.io.File

/**
 * A downloaded Node.js, made usable.
 *
 * Node runs on a device, but only through [LinkerLaunch], and that launch
 * changes two things it relies on. This class is where both are handled, so
 * that nothing above it has to know. Spike R13 measured all of it;
 * `tools/node/FINDINGS.md` is the evidence.
 *
 *  1. **Its libraries are not where it was built to look.** The binary's
 *     `RUNPATH` is `/data/data/com.termux/files/usr/lib`, a prefix this app
 *     neither has nor can create, so without `LD_LIBRARY_PATH` every run dies
 *     with `library "libicuuc.so.78" not found` -- which reads as a missing
 *     file rather than a directory that was never searched.
 *  2. **It does not know where it is.** `process.execPath` is
 *     `/system/bin/linker64`, because `/proc/self/exe` is the linker under this
 *     launch. Anything that re-invokes the runtime by that path re-invokes the
 *     *linker* with Node's arguments and fails on `expected absolute path`,
 *     which looks like a refusal to spawn and is not one -- spawning works
 *     perfectly when the child is given [runtime]. The JDK and clang are misled
 *     the same way, which makes it the route's defining property rather than
 *     Node's quirk.
 *
 * **npm is not a binary either.** `bin/npm` is a shell script that execs `node`
 * off `PATH`, and the only `node` here cannot be executed directly, so npm is
 * run as what it is: an assembly of JavaScript handed to the runtime. It is
 * also a *separate Termux package* from the runtime, so an archive assembled
 * from `nodejs-lts` alone contains no npm at all.
 */
class NodeToolchain(
    private val root: File,
    private val launch: LinkerLaunch,
) {

    /** The runtime itself, which is what a spawned child must be given. */
    val runtime: File get() = File(root, "bin/node")

    /** npm's entry point, run by [runtime] rather than through `bin/npm`. */
    val npmCli: File get() = File(root, "lib/node_modules/npm/bin/npm-cli.js")

    val isInstalled: Boolean get() = runtime.canRead() && launch.isAvailable

    /** True when this archive carries npm as well as the runtime. */
    val hasNpm: Boolean get() = npmCli.isFile

    /**
     * Runs the runtime with [arguments].
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
     * Runs npm.
     *
     * Null when this archive has no npm, rather than a plan that would fail
     * with `Cannot find module`, which names npm's own file and not the reason.
     */
    fun planNpm(
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): LaunchPlan? =
        if (!hasNpm) null else plan(listOf(npmCli.absolutePath) + arguments, environment)

    /**
     * The environment a project run needs, given the app's own directories.
     *
     * Kept separate from [plan] because only the caller knows them, and passing
     * a `Context` into a toolchain class would put Android in a layer that has
     * none.
     */
    fun prepareEnvironment(home: File, cache: File): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "TMPDIR" to cache.absolutePath,
        // npm defaults its cache to $HOME/.npm; naming it explicitly means a
        // caller can point it somewhere it is willing to have cleared.
        "npm_config_cache" to File(cache, "npm").absolutePath,
    )
}
