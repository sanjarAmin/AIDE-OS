package com.osamu.aide.toolchain.nativetools

import com.osamu.aide.core.common.DefaultDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The parts of [ClangToolchain] that are decisions rather than platform
 * behaviour: what counts as installed, which flags are produced, and how a
 * `-###` transcript is read.
 *
 * Whether the flags are *sufficient* is a question only a device answers, and
 * `ClangToolchainOnDeviceTest` asks it. These are here because they are the
 * parts that can be wrong without failing loudly -- a missing flag surfaces as
 * a header error three includes deep, and a mis-parsed transcript would run
 * whatever the last line happened to hold.
 */
class ClangToolchainTest {

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temporary.newFolder("usr")
        install()
    }

    /** A tree with the pieces [ClangToolchain.isInstalled] insists on. */
    private fun install() {
        File(root, "bin").mkdirs()
        File(root, "bin/clang").writeText("")
        File(root, "bin/clang++").writeText("")
        File(root, "bin/ld.lld").writeText("")
        File(root, "lib/clang/21/include").mkdirs()
        // The toolchain ships this beside the real directory, and it is why
        // "there is exactly one resource dir" was false on a real install.
        java.nio.file.Files.createSymbolicLink(
            File(root, "lib/clang/latest").toPath(),
            File(root, "lib/clang/21").toPath(),
        )
        File(root, "include/aarch64-linux-android").mkdirs()
        File(root, "include/c++/v1").mkdirs()
    }

    private fun toolchain(abi: String = "arm64-v8a") = ClangToolchain(
        root = root,
        abi = abi,
        launch = LinkerLaunch(File(LinkerLaunch.LINKER_64)),
        runner = NativeToolRunner(
            NativeToolchain(temporary.newFolder(), sdkInt = 34),
            DefaultDispatcherProvider(),
        ),
    )

    @Test
    fun `a complete install is usable`() {
        assertTrue(toolchain().isInstalled)
    }

    /**
     * A 550 MB archive that stopped halfway leaves a tree that looks plausible.
     * Reporting it as "not installed" is the difference between a message the
     * user can act on and a compile error inside a system header.
     */
    @Test
    fun `a half-unpacked install is not usable`() {
        File(root, "lib/clang/21").deleteRecursively()

        assertFalse(toolchain().isInstalled)
    }

    @Test
    fun `an unsupported abi is not usable`() {
        assertFalse(toolchain(abi = "armeabi-v7a").isInstalled)
        assertNull(toolchain(abi = "armeabi-v7a").triple)
    }

    /**
     * The resource directory is named for the toolchain's major version, so it
     * is discovered. Hardcoding it would break silently on the next upgrade --
     * silently because clang accepts a `-resource-dir` that does not exist.
     */
    @Test
    fun `the resource directory is discovered, not assumed`() {
        File(root, "lib/clang/latest").delete()
        File(root, "lib/clang/21").renameTo(File(root, "lib/clang/22"))

        val flags = toolchain().relocationFlags(NativeLanguage.C)

        assertEquals(File(root, "lib/clang/22").absolutePath, flags[flags.indexOf("-resource-dir") + 1])
    }

    /**
     * The real toolchain has `latest -> 21` next to `21`. Counting them as two
     * resource directories reported a perfectly good install as not installed,
     * and every on-device test then *skipped* rather than failed -- which reads
     * as green.
     */
    @Test
    fun `a version symlink beside the real directory is not a second version`() {
        assertTrue(toolchain().isInstalled)
    }

    @Test
    fun `two genuine versions are refused rather than guessed between`() {
        File(root, "lib/clang/latest").delete()
        File(root, "lib/clang/22/include").mkdirs()

        assertFalse(toolchain().isInstalled)
    }

    @Test
    fun `c flags name the sysroot and the per-triple headers`() {
        val flags = toolchain().relocationFlags(NativeLanguage.C)

        assertTrue("--sysroot=${root.absolutePath}" in flags)
        assertTrue(File(root, "include/aarch64-linux-android").absolutePath in flags)
        assertTrue("-B" in flags)
    }

    /**
     * Termux's prefix has no `usr/` level, so clang never finds libc++ on its
     * own; `#include <string>` fails while the headers sit two directories
     * away. The C compile must not carry the flag, though -- it is meaningless
     * there, and adding it would hide that the two paths differ.
     */
    @Test
    fun `only c++ gets the libc++ header path`() {
        val cxx = toolchain().relocationFlags(NativeLanguage.CXX)
        val c = toolchain().relocationFlags(NativeLanguage.C)

        assertTrue("-cxx-isystem" in cxx)
        assertTrue(File(root, "include/c++/v1").absolutePath in cxx)
        assertFalse("-cxx-isystem" in c)
    }

    @Test
    fun `the linker command is read out of a transcript`() {
        val transcript = """
            clang version 21.1.8
            InstalledDir: /apex/com.android.runtime/bin
             "/toolchain/usr/bin/ld.lld" "-shared" "-o" "out.so" "in.o"
        """.trimIndent()

        assertEquals(
            listOf("/toolchain/usr/bin/ld.lld", "-shared", "-o", "out.so", "in.o"),
            toolchain().readLinkerCommand(transcript),
        )
    }

    /**
     * A transcript whose last line is not a linker invocation is refused rather
     * than executed. Without this, a driver that changed its output -- or one
     * that planned some other tool -- would have whatever it printed run as a
     * command.
     */
    @Test
    fun `a transcript that does not end in a link is refused`() {
        assertNull(toolchain().readLinkerCommand("clang version 21.1.8\n \"/bin/something-else\" \"-x\""))
        assertNull(toolchain().readLinkerCommand(""))
    }
}
