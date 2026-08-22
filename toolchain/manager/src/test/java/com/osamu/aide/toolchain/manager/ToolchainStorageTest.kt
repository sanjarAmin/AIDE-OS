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
}
