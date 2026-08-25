package com.osamu.aide.toolchain.manager

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
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

class ComponentInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: ArchiveServer
    private lateinit var storage: ToolchainStorage
    private lateinit var license: SdkLicense
    private lateinit var installer: ComponentInstaller

    /**
     * Real dispatchers, not a test one. The installer does blocking socket and
     * file IO; running it on a scheduler that fast-forwards virtual time proves
     * nothing about either.
     */
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Default
        override val default: CoroutineDispatcher get() = Dispatchers.Default
        override val io: CoroutineDispatcher get() = Dispatchers.IO
        override val compiler: CoroutineDispatcher get() = Dispatchers.Default
    }

    @Before
    fun setUp() {
        server = ArchiveServer()
        val root = temp.newFolder("toolchains")
        storage = ToolchainStorage(root)
        license = SdkLicense(root).apply { accept() }
        installer = ComponentInstaller(storage, license, dispatchers)
    }

    @After
    fun tearDown() = server.stop()

    private suspend fun install(component: ToolchainComponent) =
        installer.install(component).toList()

    @Test
    fun `downloads, verifies and extracts the one entry that matters`() = runTest {
        val component = server.component()

        val progress = install(component)

        val installed = progress.last()
        assertTrue("install failed: $installed", installed is InstallProgress.Installed)
        val jar = (installed as InstallProgress.Installed).file
        assertEquals(storage.fileFor(component), jar)
        assertTrue(jar.isFile)
        assertEquals(300_000L, jar.length())

        // The rest of the 60 MB archive is not kept.
        assertFalse(
            "the archive was left on disk",
            storage.downloadFor(component).exists(),
        )
        assertFalse(File(jar.parentFile, "source.properties").exists())

        assertTrue(
            "no progress was reported",
            progress.filterIsInstance<InstallProgress.Downloading>().isNotEmpty(),
        )
        assertTrue(progress.contains(InstallProgress.Verifying))
        assertTrue(progress.contains(InstallProgress.Extracting))
    }

    @Test
    fun `refuses without the SDK licence, and says that is what is missing`() = runTest {
        license.revoke()

        val progress = install(server.component())

        val failure = progress.single()
        assertTrue(failure is InstallProgress.Failed)
        assertTrue(
            "the UI cannot tell this is fixable by accepting: $failure",
            (failure as InstallProgress.Failed).licenseRequired,
        )
        assertEquals("nothing should have been downloaded", 0, server.requests)
    }

    @Test
    fun `a corrupt download is rejected and deleted rather than kept`() = runTest {
        // A wrong checksum means the bytes are not what was pinned. Keeping them
        // would make every later attempt resume onto the same bad bytes and fail
        // identically, which reads as the download being permanently broken.
        val component = server.component(sha1 = "0".repeat(40))

        val progress = install(component)

        assertTrue(progress.last() is InstallProgress.Failed)
        assertFalse(
            "the corrupt archive was kept",
            storage.downloadFor(component).exists(),
        )
        assertFalse(storage.isInstalled(component))
    }

    @Test
    fun `an interrupted download resumes instead of starting over`() = runTest {
        val component = server.component()

        // First attempt: the server cuts the body short, as a dropped
        // connection would.
        server.truncateAfter = server.archive.size / 3
        val firstAttempt = install(component)
        assertTrue("expected a failure: ${firstAttempt.last()}", firstAttempt.last() is InstallProgress.Failed)

        val partial = storage.downloadFor(component)
        val resumeOffset = partial.length()
        assertTrue("nothing to resume from", resumeOffset in 1 until component.archiveBytes)

        server.truncateAfter = null
        val secondAttempt = install(component)

        assertTrue("resume failed: ${secondAttempt.last()}", secondAttempt.last() is InstallProgress.Installed)

        val resumedFrom = requireNotNull(server.rangeHeaders.last()) {
            "the second attempt sent no Range header, so it started over"
        }.removePrefix("bytes=").substringBefore('-').toLong()
        assertEquals("the second attempt did not resume where the first stopped", resumeOffset, resumedFrom)
    }

    @Test
    fun `a server that ignores the range header does not corrupt the file`() = runTest {
        // Answering a Range request with 200 and the whole body is legal. The
        // installer has to notice and restart, or it appends a second copy of
        // the archive onto the partial one and fails the checksum for ever.
        val component = server.component()

        server.truncateAfter = server.archive.size / 3
        install(component)
        server.truncateAfter = null
        server.honoursRange = false

        val progress = install(component)

        assertTrue("install failed: ${progress.last()}", progress.last() is InstallProgress.Installed)
        assertEquals(300_000L, storage.fileFor(component).length())
    }

    @Test
    fun `an archive missing the entry fails rather than installing nothing`() = runTest {
        val component = server.component()
            .copy(entries = mapOf("android-36/not-here.jar" to "android.jar"))

        val progress = install(component)

        val failure = progress.last()
        assertTrue(failure is InstallProgress.Failed)
        assertTrue(
            "the message does not name what was missing: $failure",
            (failure as InstallProgress.Failed).message.contains("not-here.jar"),
        )
        assertFalse(storage.isInstalled(component))
    }

    @Test
    fun `an already installed component is reported without touching the network`() = runTest {
        val component = server.component()
        install(component)
        val requestsAfterInstall = server.requests

        val progress = install(component)

        assertEquals(listOf(InstallProgress.Installed(storage.fileFor(component))), progress)
        assertEquals(requestsAfterInstall, server.requests)
    }
}
