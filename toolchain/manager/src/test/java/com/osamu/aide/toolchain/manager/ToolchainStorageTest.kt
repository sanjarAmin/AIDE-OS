package com.osamu.aide.toolchain.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolchainStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val storage by lazy { ToolchainStorage(temp.root) }
    private val component = ToolchainComponent.ANDROID_PLATFORM

    @Test
    fun `the component id becomes a usable directory name`() {
        // Google's ids contain a semicolon -- "platforms;android-36" -- which is
        // legal in a path and awful in one. Anything shelling out over these
        // paths later would have to quote them.
        assertFalse(storage.directoryFor(component).name.contains(';'))
        assertEquals("platforms-android-36", storage.directoryFor(component).name)
    }

    @Test
    fun `a component is installed only once its file exists`() {
        assertFalse(storage.isInstalled(component))

        storage.fileFor(component).apply {
            parentFile?.mkdirs()
            writeText("not really a jar")
        }

        assertTrue(storage.isInstalled(component))
    }

    @Test
    fun `a part-downloaded archive does not count as installed`() {
        // The download lives outside the install directory precisely so that
        // this cannot happen: a half-fetched archive must never look like a
        // usable component.
        storage.downloadFor(component).writeText("half an archive")

        assertFalse(storage.isInstalled(component))
        assertFalse(storage.downloadFor(component).startsWith(storage.directoryFor(component)))
    }

    @Test
    fun `removing takes the download with it`() {
        storage.fileFor(component).apply { parentFile?.mkdirs(); writeText("jar") }
        storage.downloadFor(component).writeText("archive")

        storage.remove(component)

        assertFalse(storage.isInstalled(component))
        assertFalse(storage.downloadFor(component).exists())
    }

    /**
     * The listing is scanned from disk, so it can name what nothing claims.
     *
     * The whole point of a "what is taking up space" screen is the entry the
     * app has forgotten about: a component whose id changed leaves its old
     * directory behind for ever, and a list built from `ToolchainComponent.ALL`
     * would never mention the 550 MB the user actually wants back.
     */
    @Test
    fun `it lists what is on disk, including what no component claims`() {
        write(storage.directoryFor(component), "android.jar", bytes = 300)
        write(temp.newFolder("clang-19-from-a-previous-life"), "bin/clang", bytes = 900)

        val installed = storage.installed()

        assertEquals(2, installed.size)
        // Largest first: on a screen about disk space, order is the answer.
        assertEquals("clang-19-from-a-previous-life", installed[0].id)
        assertEquals(900L, installed[0].bytes)
        assertEquals(null, installed[0].component)
        // And it is named by its directory, because nothing else is left to say.
        assertEquals("clang-19-from-a-previous-life", installed[0].displayName)

        assertEquals(component, installed[1].component)
        assertEquals(component.displayName, installed[1].displayName)
        assertEquals(300L, installed[1].bytes)
    }

    /** A partial download is space too, and says so rather than looking installed. */
    @Test
    fun `an abandoned download is listed as one`() {
        storage.downloadFor(component).writeBytes(ByteArray(1234))

        val only = storage.installed().single()

        assertTrue("a partial download was not marked as one", only.isPartialDownload)
        assertEquals(component, only.component)
        assertEquals(1234L, only.bytes)
        assertFalse("it must not look installed", storage.isInstalled(component))
    }

    /**
     * The licence marker lives here too and is not a toolchain.
     *
     * `SdkLicense` writes it beside the components it permitted, deliberately,
     * so that clearing app data clears both. A listing that offered it as
     * something to delete would be offering to un-accept Google's terms from a
     * screen about disk space.
     */
    @Test
    fun `the sdk licence marker is not offered as a toolchain`() {
        SdkLicense(temp.root).accept()

        assertEquals(emptyList<InstalledToolchain>(), storage.installed())
    }

    /**
     * An install with a leftover download beside it is **one** row, not two.
     *
     * Both exist whenever a download was interrupted and later completed, and
     * two rows carrying the same name would read as a bug in the screen rather
     * than as two files on disk.
     */
    @Test
    fun `a directory and its leftover download are one entry`() {
        write(storage.directoryFor(component), "android.jar", bytes = 300)
        storage.downloadFor(component).writeBytes(ByteArray(40))

        val only = storage.installed().single()

        assertEquals(340L, only.bytes)
        assertFalse("a finished install was called a partial download", only.isPartialDownload)
    }

    @Test
    fun `removing an entry takes its directory and its partial download`() {
        write(storage.directoryFor(component), "android.jar", bytes = 10)
        storage.downloadFor(component).writeBytes(ByteArray(10))

        storage.installed().forEach { storage.removeInstalled(it) }

        assertEquals(emptyList<InstalledToolchain>(), storage.installed())
        assertFalse(storage.directoryFor(component).exists())
        assertFalse(storage.downloadFor(component).exists())
    }

    /**
     * A symlink is not counted, because what it points at already is.
     *
     * Every toolchain here ships them -- Node's `bin/npm` points into
     * `lib/node_modules`, mono's `bin/mono` at `mono-sgen` -- and
     * `File.walkTopDown()` follows them. It reported Node at 184 MB where `du`
     * said 119, which on the one screen whose whole purpose is a disk figure is
     * not a rounding error.
     */
    @Test
    fun `a symlink is not counted twice`() {
        val directory = storage.directoryFor(component)
        write(directory, "lib/real.so", bytes = 500)
        java.nio.file.Files.createSymbolicLink(
            java.io.File(directory, "bin-link").toPath(),
            java.io.File(directory, "lib").toPath(),
        )

        assertEquals(500L, storage.installed().single().bytes)
    }

    private fun write(directory: java.io.File, path: String, bytes: Int) {
        val file = java.io.File(directory, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }
}
