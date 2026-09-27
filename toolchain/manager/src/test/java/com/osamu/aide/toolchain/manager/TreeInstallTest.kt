package com.osamu.aide.toolchain.manager

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Installing a component that is a tree rather than a handful of files.
 *
 * What matters here is not that bytes arrive -- [ComponentInstallerTest] covers
 * the download -- but that the tree still *is* a toolchain afterwards. A
 * extraction that flattened symlinks or dropped permission bits would produce a
 * directory of the right size that cannot compile anything, and would fail much
 * later with a message pointing somewhere else entirely.
 */
class TreeInstallTest {

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var server: TarballServer
    private lateinit var storage: ToolchainStorage
    private lateinit var installer: ComponentInstaller

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val compiler = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        server = TarballServer()
        val root = temporary.newFolder()
        storage = ToolchainStorage(root)
        // Accepted, though the toolchain does not require it: LLVM is
        // Apache-2.0 and Google's terms have no bearing on it. Accepting here
        // keeps this test about extraction rather than about the licence gate,
        // which ComponentInstallerTest already covers.
        installer = ComponentInstaller(storage, SdkLicense(root).apply { accept() }, dispatchers)
    }

    @After
    fun tearDown() = server.stop()

    private suspend fun install(component: ToolchainComponent) =
        installer.install(component).toList()

    @Test
    fun `the tree is installed whole`() = runTest {
        val component = server.component()

        val progress = install(component)

        assertTrue("install did not finish: ${progress.last()}", progress.last() is InstallProgress.Installed)
        val root = storage.directoryFor(component)
        assertEquals("a compiler, allegedly", File(root, "usr/bin/clang-21").readText())
        assertEquals("not executable", File(root, "usr/README").readText())
    }

    /**
     * The single most important property of this extraction.
     *
     * A clang driver decides which language it compiles from the name it was
     * invoked under, so `clang++` being a link is the mechanism, not a way of
     * saving space. An extractor that resolved links would produce a tree that
     * compiles C when asked for C++ -- which is not an error, just wrong output.
     */
    @Test
    fun `symlinks are recreated as symlinks`() = runTest {
        val component = server.component()

        install(component)

        val link = File(storage.directoryFor(component), "usr/bin/clang")
        assertTrue("clang was materialised as a copy, not a link", Files.isSymbolicLink(link.toPath()))
        assertEquals("clang-21", Files.readSymbolicLink(link.toPath()).toString())
    }

    /**
     * `clang++ -> clang -> clang-21`, which is the real toolchain's shape and
     * the one that has already caused a bug: a chain resolves only if every
     * link in it was recreated in the right order.
     */
    @Test
    fun `a chain of symlinks resolves to the file at the end`() = runTest {
        val component = server.component()

        install(component)

        assertEquals(
            "a compiler, allegedly",
            File(storage.directoryFor(component), "usr/bin/clang++").readText(),
        )
    }

    /**
     * A compiler that arrives without its executable bit fails with a
     * permission error from deep inside a build, nowhere near the install.
     */
    @Test
    fun `the executable bit survives, and is not handed out freely`() = runTest {
        val component = server.component()

        install(component)

        val root = storage.directoryFor(component)
        assertTrue("the compiler is not executable", File(root, "usr/bin/clang-21").canExecute())
        assertFalse("a plain file became executable", File(root, "usr/README").canExecute())
    }

    /**
     * The marker is the whole of what "installed" means, so an archive that
     * does not contain it has to fail rather than leave a tree behind that
     * every later check reports as missing.
     */
    @Test
    fun `an archive without the marker fails rather than installing nothing`() = runTest {
        val component = server.component(marker = "usr/bin/not-here")

        val failure = install(component).last()

        assertTrue(failure is InstallProgress.Failed)
        assertTrue(
            "the message does not name what was missing: $failure",
            (failure as InstallProgress.Failed).message.contains("not-here"),
        )
        assertFalse(storage.isInstalled(component))
    }

    /**
     * A tar can name `../` and write outside the directory it is extracted
     * into. Ours does not; this asserts that one which did would be refused
     * rather than trusted for being ours.
     */
    @Test
    fun `an entry that escapes the install directory is refused`() = runTest {
        server.stop()
        server = TarballServer(escaping = true)
        val component = server.component()

        val failure = install(component).last()

        assertTrue("an escaping archive was accepted: $failure", failure is InstallProgress.Failed)
        assertFalse(
            "a file was written outside the install directory",
            File(storage.directoryFor(component).parentFile, "escaped").exists(),
        )
    }

    /**
     * Nothing is left behind to be mistaken for an install. The staging
     * directory is a sibling of the real one, so a leftover would sit in the
     * same place the next attempt looks.
     */
    @Test
    fun `a failed install leaves no partial tree`() = runTest {
        val component = server.component(marker = "usr/bin/not-here")

        install(component)

        val leftovers = storage.directoryFor(component).parentFile
            ?.listFiles()
            ?.filter { it.name.endsWith(".partial") }
            .orEmpty()
        assertTrue("staging directories were left behind: $leftovers", leftovers.isEmpty())
    }
}
