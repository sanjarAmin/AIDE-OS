package com.osamu.aide.toolchain.manager

import java.io.File
import java.nio.file.Files

/**
 * Where installed components live, and what is already there.
 *
 * Under `filesDir`, not `cacheDir`: the system may clear a cache at any moment,
 * and a 63 MB download disappearing between two builds is not a cache miss the
 * user would forgive. It is excluded from backup for the same reason it is not
 * bundled -- it is large, and re-downloadable.
 */
class ToolchainStorage(private val root: File) {

    fun directoryFor(component: ToolchainComponent): File =
        File(root, component.id.replace(';', '-'))

    /** The installed file itself, whether or not it exists yet. */
    /** The component's principal file; see [ToolchainComponent.primaryInstalledName]. */
    fun fileFor(component: ToolchainComponent): File =
        File(directoryFor(component), component.primaryInstalledName)

    /** One named file inside an installed component. */
    fun fileFor(component: ToolchainComponent, installedName: String): File =
        File(directoryFor(component), installedName)

    fun isInstalled(component: ToolchainComponent): Boolean =
        fileFor(component).isFile

    /**
     * Somewhere to download to.
     *
     * Beside the install directory rather than inside it, so that a partial
     * download is never mistaken for an installed component -- and so that
     * resuming one is a matter of finding the file still there.
     */
    fun downloadFor(component: ToolchainComponent): File =
        File(root, "${component.id.replace(';', '-')}.download").also {
            it.parentFile?.mkdirs()
        }

    fun remove(component: ToolchainComponent) {
        directoryFor(component).deleteRecursively()
        downloadFor(component).delete()
    }

    /**
     * What is on disk, whether or not this app still knows what it is.
     *
     * Scanned from the directory rather than from [ToolchainComponent.ALL],
     * because the entry worth the most disk is the one nothing claims any more:
     * a component whose id changed leaves its old directory behind for ever,
     * and a list built from what the app expects would never mention it. Those
     * come back with a null [InstalledToolchain.component].
     *
     * A partial download counts too. Resuming one is deliberate -- a 150 MB
     * download interrupted on a train should not start over -- and the cost is
     * that an abandoned one sits there indefinitely.
     */
    fun installed(): List<InstalledToolchain> {
        val known = ToolchainComponent.ALL.associateBy { it.id.replace(';', '-') }
        return root.listFiles().orEmpty()
            .filter { it.name != LICENSE_MARKER }
            // Grouped, because a component interrupted and then reinstalled
            // has both a directory and a leftover `.download`, and two rows
            // with the same name reads as a bug rather than as two files.
            .groupBy { it.name.removeSuffix(DOWNLOAD_SUFFIX) }
            .map { (id, entries) ->
                val component = known[id]
                InstalledToolchain(
                    id = id,
                    // The component's own name where there is one; the
                    // directory otherwise, which is all that is left to say.
                    displayName = component?.displayName ?: id,
                    bytes = entries.sumOf { sizeOf(it) },
                    // Only when that is *all* there is. A finished install with
                    // a stray partial beside it is installed.
                    isPartialDownload = entries.none { it.isDirectory },
                    component = component,
                )
            }
            .sortedByDescending { it.bytes }
    }

    /** Deletes one entry from [installed], known to this app or not. */
    fun removeInstalled(entry: InstalledToolchain) {
        File(root, entry.id).deleteRecursively()
        // Unconditionally: a row can be an install *and* an abandoned download,
        // and freeing only the part the flag named would leave the rest.
        File(root, entry.id + DOWNLOAD_SUFFIX).delete()
    }

    /**
     * Bytes under [file], **not following symlinks**.
     *
     * `File.walkTopDown()` follows them, and every toolchain here is full of
     * them: Node ships `bin/npm` pointing into `lib/node_modules`, mono's
     * `bin/mono` points at `mono-sgen`. Walking through counted Node at 184 MB
     * where `du` said 119 -- a 55 % overstatement on the one number this screen
     * exists to report. Following them also risks a cycle, which would hang the
     * measurement rather than exaggerate it.
     *
     * A symlink contributes nothing: the bytes it points at are already counted
     * where they live, or belong to something else entirely.
     */
    private fun sizeOf(file: File): Long = when {
        Files.isSymbolicLink(file.toPath()) -> 0
        file.isFile -> file.length()
        else -> file.listFiles().orEmpty().sumOf { sizeOf(it) }
    }

    private companion object {
        const val DOWNLOAD_SUFFIX = ".download"

        /** Not a toolchain: [SdkLicense]'s acceptance record lives here too. */
        const val LICENSE_MARKER = "android-sdk-license.accepted"
    }
}

/**
 * One thing taking up space under the toolchains root.
 *
 * [component] is null when nothing in [ToolchainComponent.ALL] claims this
 * directory any more -- an old version left behind by a re-pin. It can still be
 * removed; it just cannot be described or reinstalled.
 */
data class InstalledToolchain(
    val id: String,
    val displayName: String,
    val bytes: Long,
    val isPartialDownload: Boolean,
    val component: ToolchainComponent?,
)
