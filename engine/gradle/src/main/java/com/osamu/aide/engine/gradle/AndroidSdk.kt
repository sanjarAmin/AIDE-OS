package com.osamu.aide.engine.gradle

import java.io.File
import java.nio.file.Files

/**
 * The Android SDK, as AGP needs to find it when Gradle runs on the device.
 *
 * Three things have to be true before AGP will build, and none of them is true
 * by default here. This is what makes them true, so the engine's callers do not
 * have to know any of it.
 */
class AndroidSdk(
    /** The SDK root — the directory holding `platforms/`. */
    val dir: File,
    /**
     * `libaapt2.so` from the app's `nativeLibraryDir`.
     *
     * The one aapt2 that can run here. See [aapt2Override] for why it cannot
     * simply be handed to AGP under that name.
     */
    private val bundledAapt2: File,
    /** Where the `aapt2`-named link is kept. Ours, not the user's project. */
    private val linkDir: File,
) {

    val platformJar: File?
        get() = File(dir, "platforms").listFiles()
            ?.sortedByDescending { it.name }
            ?.map { File(it, "android.jar") }
            ?.firstOrNull { it.isFile }

    /**
     * Whether the SDK licence has been accepted.
     *
     * AGP refuses to build without it and says so in its own words, but only
     * after Gradle has started, configured and begun executing — which on this
     * hardware is most of a minute before the user learns they needed to tap a
     * button. Checked up front instead.
     */
    val licenceAccepted: Boolean
        get() = File(dir, "licenses/android-sdk-license").isFile

    val isUsable: Boolean get() = platformJar != null && licenceAccepted

    /**
     * A path to hand to `android.aapt2FromMavenOverride`.
     *
     * AGP resolves aapt2 from Maven, and what it gets is a **Linux x86_64**
     * binary that cannot run on this device at all. Ours can, but AGP insists
     * the file be named `aapt2` — an `.so` under any other name is rejected
     * before it is ever executed — and the app's own `nativeLibraryDir` is
     * read-only, so the file cannot be renamed where it lives.
     *
     * Hence a symlink, refreshed on every build: the target moves when the app
     * is reinstalled or updated, and a link left pointing at the previous
     * install fails as a missing binary rather than as a stale link.
     *
     * Null when there is no bundled aapt2 to point at, which is a device below
     * the toolchain's API floor rather than a broken install.
     */
    fun aapt2Override(): File? {
        if (!bundledAapt2.isFile) return null
        linkDir.mkdirs()
        val link = File(linkDir, "aapt2")
        link.delete()
        return runCatching {
            Files.createSymbolicLink(link.toPath(), bundledAapt2.toPath())
            link
        }.getOrNull()
    }

    /**
     * Points the project at this SDK, keeping whatever else it had.
     *
     * **An imported Android Studio project already has a `local.properties`,
     * and its `sdk.dir` is a path on somebody's desktop.** That is not an edge
     * case — it is the ordinary state of the projects M9 exists to build, and
     * `sdk.dir` beats `ANDROID_HOME` in AGP's own search order, so setting the
     * environment variable and hoping is not enough. The stale line has to go.
     *
     * Rewriting it is legitimate where rewriting `gradle.properties` would not
     * be: `local.properties` is machine-specific by definition, gitignored by
     * every Android template, and Android Studio rewrites it for exactly this
     * reason. Everything else in the file is left alone, because a project may
     * keep its own keys there — the NDK path, a signing location — and losing
     * them silently would be worse than the problem this solves.
     */
    fun pointProjectAtSdk(projectRoot: File) {
        val file = File(projectRoot, "local.properties")
        val kept = if (file.isFile) {
            file.readLines().filterNot { it.trimStart().startsWith("sdk.dir") }
        } else {
            emptyList()
        }
        file.writeText((kept + "sdk.dir=${dir.absolutePath}").joinToString("\n") + "\n")
    }
}
