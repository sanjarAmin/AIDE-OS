package com.osamu.aide.engine.gradle

import android.content.Context
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.toolchain.nativetools.JvmToolchain
import java.io.File
import java.nio.file.Files

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
            sdk = androidSdk(),
        ).takeIf { it.isInstalled }
    }

    /**
     * An SDK laid out the way AGP expects, assembled from installed components.
     *
     * There is no "Android SDK" download. What `:toolchain:manager` installs is
     * a platform (in practice one file, `android.jar`) and a build-tools
     * directory, each in its own component directory — and AGP will not look at
     * either unless they sit under one root as
     * `platforms/android-NN/android.jar` and `build-tools/NN/`.
     *
     * So the root is composed here, out of symlinks, rather than by downloading
     * the same bytes a second time in a different shape. **`android.jar` alone
     * is enough for the platform**: building with the rest of the platform
     * directory deleted — `data/`, `optional/`, `skins/`, `templates/`,
     * `core-for-system-modules.jar` — succeeds, which is what makes the
     * existing platform component reusable as-is.
     *
     * The licence file is written rather than downloaded. It is not a
     * permission we grant on the user's behalf: `:toolchain:manager` has
     * already refused to install either component until the user accepted the
     * same terms, and this file is only how AGP is told that happened.
     */
    fun androidSdk(): AndroidSdk? {
        val platformJar = File(installRoot, "${PLATFORM_COMPONENT_ID}/android.jar")
        val buildTools = File(installRoot, "$BUILD_TOOLS_COMPONENT_ID/$BUILD_TOOLS_REVISION")
        if (!platformJar.isFile || !buildTools.isDirectory) return null

        val root = File(context.filesDir, ANDROID_SDK_HOME)
        return runCatching {
            link(File(root, "platforms/$PLATFORM_DIRECTORY/android.jar"), platformJar)
            link(File(root, "build-tools/$BUILD_TOOLS_REVISION"), buildTools)
            File(root, "licenses").mkdirs()
            // The leading newline and the absence of a trailing one are the
            // format sdkmanager writes, and it is compared literally enough
            // that copying it exactly is cheaper than finding out it is not.
            File(root, "licenses/android-sdk-license")
                .writeText(SDK_LICENCE_HASHES.joinToString("\n", prefix = "\n"))
            AndroidSdk(
                dir = root,
                bundledAapt2 = File(context.applicationInfo.nativeLibraryDir, AAPT2_LIBRARY),
                linkDir = File(context.filesDir, "$GRADLE_USER_HOME/bin"),
            )
        }.getOrNull()
    }

    /** Refreshed every time: a component reinstall moves what it points at. */
    private fun link(at: File, target: File) {
        at.parentFile?.mkdirs()
        Files.deleteIfExists(at.toPath())
        Files.createSymbolicLink(at.toPath(), target.toPath())
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

        /** Likewise agreed with `:toolchain:manager`; the same test checks. */
        const val PLATFORM_COMPONENT_ID = "platforms;android-36"
        const val BUILD_TOOLS_COMPONENT_ID = "build-tools;36.0.0"
        const val BUILD_TOOLS_REVISION = "36.0.0"

        /**
         * The platform directory AGP looks for.
         *
         * Its name is the API level, and it has to agree with the `compileSdk`
         * the project declares — not with the component's own id, which happens
         * to spell the same thing differently.
         */
        const val PLATFORM_DIRECTORY = "android-36"

        /** Where the composed SDK lives; nothing outside this class writes it. */
        const val ANDROID_SDK_HOME = "android-sdk"

        const val AAPT2_LIBRARY = "libaapt2.so"

        /**
         * The hashes of the SDK terms, which is all a licence file contains.
         *
         * **Two, not one, and the format is exact.** `sdkmanager` writes a
         * leading newline and then one hash per line with no trailing newline,
         * and it accepts the file if *any* line matches the terms it is asking
         * about. The terms have been revised, so a device that accepts only the
         * older hash fails against a current SDK with "some licences have not
         * been accepted" — a message that says nothing about which, or that a
         * hash is what it is comparing.
         *
         * Both are listed for the same reason `sdkmanager` lists several: it is
         * the only way one file satisfies more than one revision of the terms.
         */
        val SDK_LICENCE_HASHES = listOf(
            "8933bad161af4178b1185d1a37fbf41ea5269c55",
            "24333f8a63b6825ea9c5514f83c2829b004d1fee",
        )
    }
}
