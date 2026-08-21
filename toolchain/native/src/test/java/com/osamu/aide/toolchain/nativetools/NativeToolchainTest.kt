package com.osamu.aide.toolchain.nativetools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The API gate and lookup logic, tested off-device. The exec path itself needs
 * a real runtime and lives in Aapt2InstrumentedTest.
 */
class NativeToolchainTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun libDirWithAapt2(executable: Boolean = true): File {
        val dir = temp.newFolder("lib")
        File(dir, NativeTool.AAPT2.libraryName).apply {
            writeText("not really a binary")
            setExecutable(executable)
        }
        return dir
    }

    @Test
    fun `aapt2 is available on api 30`() {
        val toolchain = NativeToolchain(libDirWithAapt2(), sdkInt = 30)
        assertTrue(toolchain.locate(NativeTool.AAPT2) is ToolAvailability.Available)
        assertTrue(toolchain.isAvailable(NativeTool.AAPT2))
    }

    @Test
    fun `aapt2 is gated off below api 30 even when the binary is present`() {
        val toolchain = NativeToolchain(libDirWithAapt2(), sdkInt = 29)

        val availability = toolchain.locate(NativeTool.AAPT2)

        assertTrue(availability is ToolAvailability.UnsupportedApiLevel)
        availability as ToolAvailability.UnsupportedApiLevel
        assertEquals(30, availability.required)
        assertEquals(29, availability.actual)
        assertFalse(toolchain.isAvailable(NativeTool.AAPT2))
    }

    @Test
    fun `the api gate is checked before the file, so old devices get the useful message`() {
        // Empty dir: the binary is missing *and* the API is too low. The API
        // reason is the actionable one for the user.
        val toolchain = NativeToolchain(temp.newFolder("empty"), sdkInt = 26)
        assertTrue(toolchain.locate(NativeTool.AAPT2) is ToolAvailability.UnsupportedApiLevel)
    }

    @Test
    fun `a missing binary is reported with the path it was expected at`() {
        val dir = temp.newFolder("empty2")
        val toolchain = NativeToolchain(dir, sdkInt = 34)

        val availability = toolchain.locate(NativeTool.AAPT2)

        assertTrue(availability is ToolAvailability.Missing)
        assertEquals(
            File(dir, "libaapt2.so").absolutePath,
            (availability as ToolAvailability.Missing).expectedAt.absolutePath,
        )
    }

    @Test
    fun `a non-executable binary is distinguished from a missing one`() {
        val toolchain = NativeToolchain(libDirWithAapt2(executable = false), sdkInt = 34)
        assertTrue(toolchain.locate(NativeTool.AAPT2) is ToolAvailability.NotExecutable)
    }

    @Test
    fun `bundled tools use the lib-prefixed so name required for jniLibs packaging`() {
        NativeTool.entries.forEach { tool ->
            assertTrue(
                "${tool.name} must be named lib*.so to be packaged and extracted",
                tool.libraryName.startsWith("lib") && tool.libraryName.endsWith(".so"),
            )
        }
    }
}
