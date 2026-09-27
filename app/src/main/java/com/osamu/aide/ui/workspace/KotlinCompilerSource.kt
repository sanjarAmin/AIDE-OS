package com.osamu.aide.ui.workspace

import com.osamu.aide.engine.fast.KotlinCompiler
import com.osamu.aide.engine.fast.KotlinToolchainProvider
import java.io.File

/**
 * The Kotlin compiler, on the devices that have one.
 *
 * This exists instead of a `single<KotlinCompiler?>` because **Koin cannot hold
 * null in a singleton**. A definition that returns null resolves as
 * `IllegalStateException: Single instance created couldn't return value`, and
 * because `ProjectBuilder` depends on it, that took the whole graph down the
 * first time a project was opened -- on any device without the compiler
 * installed, which is the normal state: the archive is 54 MB and downloaded on
 * demand. Nothing caught it, because the null branch is the *uncommon* one on a
 * development machine and every unit test builds its own objects.
 *
 * Resolved on use rather than at construction, so a toolchain installed from
 * inside the app is picked up on the next build rather than the next launch.
 * Success is memoised because the compiler's ~11 s startup is paid per
 * classloader; failure is not, because re-checking is a stat call.
 */
class KotlinCompilerSource(
    private val toolchains: KotlinToolchainProvider,
    private val hostDirectory: File,
) {

    @Volatile
    private var cached: KotlinCompiler? = null

    @Synchronized
    fun compiler(): KotlinCompiler? {
        cached?.let { return it }
        val toolchain = toolchains.toolchain() ?: return null
        return KotlinCompiler(toolchain, hostDirectory).also { cached = it }
    }
}
