package com.osamu.aide.toolchain.nativetools

/**
 * A process to start: the argv, and what to add to its environment.
 *
 * The indirection exists because this project has two unrelated ways of
 * starting a native tool and only one way of running one. A bundled tool in
 * `nativeLibraryDir` is executed directly; a downloaded one in app-private
 * storage cannot be executed at all and has to be handed to the dynamic linker
 * (see [LinkerLaunch] and `tools/nativeexec/FINDINGS.md`). Both produce a plan,
 * and [NativeToolRunner] runs a plan without knowing which it was given.
 *
 * [environment] is *added* to the inherited environment rather than replacing
 * it. A cleared environment loses `ANDROID_DATA` and `PATH`, which the platform
 * linker and the tools themselves read.
 */
data class LaunchPlan(
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(command.isNotEmpty()) { "a launch plan needs at least an executable" }
    }
}
