package com.osamu.aide.toolchain.manager

import java.io.File

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
    fun fileFor(component: ToolchainComponent): File =
        File(directoryFor(component), component.installedName)

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
}
