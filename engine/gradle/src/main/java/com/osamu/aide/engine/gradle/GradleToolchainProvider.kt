package com.osamu.aide.engine.gradle

import android.content.Context
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.toolchain.nativetools.JvmToolchain
import java.io.File

/**
 * Finds the JDK and the Gradle distribution, if this device has them.
 *
 * The same arrangement as `:engine:fast`'s Kotlin and C/C++ providers, and
 * absence means the same thing: an ordinary state. Between them these are
 * roughly 470 MB, most projects build faster on the fast engine, and a device
 * that has never needed Gradle should not be carrying it.
 *
 * The install directories are derived rather than shared with
 * `:toolchain:manager`, for the reason the other providers give: the engine
 * builds with what it is given and does not know how components arrive. The
 * cost is a convention agreed in two places, and a test asserts both sides.
 */
class GradleToolchainProvider(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val installRoot: File = File(context.filesDir, "toolchains"),
) {

    /**
     * A ready engine, or null.
     *
     * Returns null when either half is missing rather than an engine that
     * refuses on use: the caller has to choose between engines before a build
     * starts, and "there is no Gradle here" is that decision's input.
     */
    fun engine(): GradleBuildSystem? {
        val javaHome = javaHome() ?: return null
        val gradleHome = gradleHome() ?: return null
        val jvm = JvmToolchain.from(context, javaHome, dispatchers)
        if (!jvm.isInstalled) return null

        return GradleBuildSystem(
            jvm = jvm,
            gradleHome = gradleHome,
            dispatchers = dispatchers,
            gradleUserHome = File(context.filesDir, GRADLE_USER_HOME),
        ).takeIf { it.isInstalled }
    }

    /**
     * The JDK, found rather than named.
     *
     * The directory inside carries the version — `java-21-openjdk` — so
     * hardcoding it would break on the next JDK for no benefit. There is
     * exactly one; two would mean an interrupted upgrade, and picking either
     * would be a guess.
     */
    fun javaHome(): File? = File(installRoot, "$JDK_COMPONENT_ID/lib/jvm")
        .listFiles()
        ?.singleOrNull { it.isDirectory }

    /** Likewise: the distribution unpacks to a directory named for its version. */
    fun gradleHome(): File? = File(installRoot, GRADLE_COMPONENT_ID)
        .listFiles()
        ?.singleOrNull { it.isDirectory && it.name.startsWith("gradle-") }

    /**
     * Points the JDK's own binaries at ours.
     *
     * Called once after installing, not per build: Gradle forks its daemon by
     * exec'ing `$java.home/bin/java` itself, so the path has to be right before
     * a build starts rather than while one runs.
     */
    fun prepareJdk() = javaHome()?.let { JvmToolchain.from(context, it, dispatchers).prepare() }

    companion object {
        /** Must match the ids `:toolchain:manager` installs under; a test checks. */
        const val JDK_COMPONENT_ID = "openjdk-21"
        const val GRADLE_COMPONENT_ID = "gradle"

        /**
         * Gradle's caches, under the app's files.
         *
         * Not the user's home: on Android that is not a directory anything
         * should write to, and Gradle would otherwise scatter a `.gradle` tree
         * somewhere nothing cleans up.
         */
        const val GRADLE_USER_HOME = "gradle-home"
    }
}
