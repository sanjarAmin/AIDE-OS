package com.osamu.aide.toolchain.nativetools

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The decisions [JvmToolchain] makes, off-device: what counts as installed, and
 * what `prepare` does to a JDK.
 *
 * Whether the substitution is *sufficient* is a question only a device answers,
 * and `JvmToolchainOnDeviceTest` asks it. These cover the parts that can be
 * wrong without failing loudly -- a redirect that quietly did nothing leaves a
 * JDK that builds fine until something tries to spawn.
 */
class JvmToolchainTest {

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var javaHome: File
    private lateinit var nativeLibs: File

    @Before
    fun setUp() {
        javaHome = temporary.newFolder("jdk")
        File(javaHome, "lib/server").mkdirs()
        File(javaHome, "bin").mkdirs()
        File(javaHome, "lib/server/libjvm.so").writeText("")
        File(javaHome, "lib/modules").writeText("")
        File(javaHome, "lib/jspawnhelper").writeText("the JDK's own")
        JvmToolchain.REDIRECTED_TOOLS.forEach { File(javaHome, "bin/$it").writeText("stock $it") }

        nativeLibs = temporary.newFolder("nativeLibs")
        File(nativeLibs, JvmToolchain.LAUNCHER).apply { writeText("launcher"); setExecutable(true) }
        File(nativeLibs, JvmToolchain.SPAWN_HELPER).apply { writeText("helper"); setExecutable(true) }
    }

    private fun toolchain() = JvmToolchain(
        javaHome = javaHome,
        nativeLibraryDir = nativeLibs,
        runner = NativeToolRunner(
            NativeToolchain(temporary.newFolder(), sdkInt = 34),
            DefaultDispatcherProvider(),
        ),
    )

    @Test
    fun `a complete jdk with a launcher beside it is usable`() {
        assertTrue(toolchain().isInstalled)
    }

    /**
     * A 300 MB archive that stopped halfway leaves a tree that looks plausible.
     * Saying "not installed" is the difference between a message the user can
     * act on and a failure inside a compile.
     */
    @Test
    fun `a half-unpacked jdk is not usable`() {
        File(javaHome, "lib/modules").delete()

        assertFalse(toolchain().isInstalled)
    }

    /** Without our launcher the JDK cannot be started at all, whatever it holds. */
    @Test
    fun `a jdk without the launcher is not usable`() {
        File(nativeLibs, JvmToolchain.LAUNCHER).delete()

        assertFalse(toolchain().isInstalled)
    }

    @Test
    fun `prepare points every redirected tool at the launcher`() {
        assertTrue(toolchain().prepare() is AppResult.Success)

        JvmToolchain.REDIRECTED_TOOLS.forEach { tool ->
            val link = File(javaHome, "bin/$tool")
            assertTrue("$tool was not redirected", Files.isSymbolicLink(link.toPath()))
            assertEquals(
                "$tool points somewhere unexpected",
                File(nativeLibs, JvmToolchain.LAUNCHER).absolutePath,
                Files.readSymbolicLink(link.toPath()).toString(),
            )
        }
    }

    /**
     * The spawn helper is the one that makes every *other* JVM work -- the
     * Gradle daemon, the Kotlin daemon, anything a build forks. Missing it is
     * not visible until something spawns.
     */
    @Test
    fun `prepare points the spawn helper at the shipped one`() {
        toolchain().prepare()

        val link = File(javaHome, "lib/jspawnhelper")
        assertTrue("the spawn helper was not redirected", Files.isSymbolicLink(link.toPath()))
        assertEquals(
            File(nativeLibs, JvmToolchain.SPAWN_HELPER).absolutePath,
            Files.readSymbolicLink(link.toPath()).toString(),
        )
    }

    /** The originals stay beside the links, so the change can be seen and undone. */
    @Test
    fun `prepare keeps what it replaced`() {
        toolchain().prepare()

        assertEquals("stock java", File(javaHome, "bin/java.real").readText())
        assertEquals("the JDK's own", File(javaHome, "lib/jspawnhelper.real").readText())
    }

    /**
     * Called twice -- an install followed by an app restart, say -- it must not
     * replace the kept original with a link to the launcher, which would lose
     * the real binary for good.
     */
    @Test
    fun `prepare is idempotent and does not eat the original`() {
        toolchain().prepare()
        toolchain().prepare()

        assertEquals("stock java", File(javaHome, "bin/java.real").readText())
        assertTrue(Files.isSymbolicLink(File(javaHome, "bin/java").toPath()))
    }

    /**
     * A JDK that ships fewer tools is not broken. Demanding all of them would
     * report a working install as a failure.
     */
    @Test
    fun `a missing tool is skipped rather than failing the whole prepare`() {
        File(javaHome, "bin/jmod").delete()

        assertTrue(toolchain().prepare() is AppResult.Success)
        assertFalse(File(javaHome, "bin/jmod").exists())
        assertTrue(Files.isSymbolicLink(File(javaHome, "bin/java").toPath()))
    }

    @Test
    fun `prepare refuses when the launcher was not packaged`() {
        File(nativeLibs, JvmToolchain.LAUNCHER).delete()

        val result = toolchain().prepare()

        assertTrue(result is AppResult.Failure)
        assertTrue(
            "the message does not say what is missing",
            (result as AppResult.Failure).error.message.contains("launcher"),
        )
    }

    /**
     * The library path has to include the prefix two levels above the JDK:
     * libjvm is linked against Termux's libz and its shared-memory shim, and
     * without them the VM does not load at all.
     */
    @Test
    fun `the default environment points at the jdk and the prefix above it`() {
        val environment = toolchain().defaultEnvironment()

        val path = environment.getValue(LinkerLaunch.LD_LIBRARY_PATH)
        assertTrue("no server VM directory: $path", path.contains("lib/server"))
        assertTrue("JAVA_HOME is not set", environment.containsKey("JAVA_HOME"))
        assertEquals(javaHome.absolutePath, environment["JAVA_HOME"])
    }
}
