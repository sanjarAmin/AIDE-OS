package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.FileProjectRepository
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.editor.DocumentStore
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import com.osamu.aide.engine.fast.ApkInstaller
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The workspace's wiring, on a device: project -> file tree -> editor -> build.
 *
 * Instrumented rather than a JVM test because every collaborator this screen
 * has needs a real [android.content.Context] -- the installer, the toolchain
 * manager, the native tool runner. Mocking them out would leave only the parts
 * that were never in doubt.
 *
 * What it does not do is run a build. That needs the 63 MB platform and is
 * already proved end to end by :engine:fast's DownloadedPlatformBuildTest; the
 * question here is whether the screen reaches the engine at all.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private lateinit var workspaceRoot: File
    private lateinit var repository: FileProjectRepository
    private lateinit var project: Project
    private lateinit var viewModel: WorkspaceViewModel

    @Before
    fun setUp() = runBlocking {
        workspaceRoot = File(context.cacheDir, "workspace-test-${System.nanoTime()}")
        repository = FileProjectRepository(workspaceRoot, dispatchers)

        project = (
            repository.createProject(
                // Not the directory name: "Demo App" becomes the directory
                // "Demo-App", so a test that waits for the *project* name is
                // really waiting for the descriptor to have been read.
                name = "Demo App",
                applicationId = "com.example.demo",
                language = SourceLanguage.JAVA,
                engine = BuildEngine.FAST,
            ) as AppResult.Success
            ).value

        val toolchain = ToolchainManager(context, dispatchers)
        viewModel = WorkspaceViewModel(
            dispatchers = dispatchers,
            projects = repository,
            documents = DocumentStore(dispatchers),
            builder = ProjectBuilder(
                toolchain = toolchain,
                platforms = AndroidPlatformProvider(context, dispatchers),
                runner = NativeToolRunner(NativeToolchain.from(context), dispatchers),
                dispatchers = dispatchers,
                outputRoot = File(context.cacheDir, "builds-test"),
            ),
            toolchain = toolchain,
            installer = ApkInstaller(context, dispatchers),
        )
        Unit
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    /** ViewModel work runs on the main dispatcher; nothing observable happens off it. */
    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private fun awaitState(what: String, predicate: (WorkspaceUiState) -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (predicate(viewModel.state.value)) return@runBlocking
            withContext(Dispatchers.IO) { Thread.sleep(POLL_MILLIS) }
        }
        throw AssertionError("timed out waiting for $what; state was ${viewModel.state.value}")
    }

    private val mainActivitySource: File
        get() = File(project.rootDir, "src/main/java/com/example/demo/MainActivity.java")

    @Test
    fun opening_a_project_names_it_and_lists_its_files() {
        onMain { viewModel.open(project.rootDir) }

        awaitState("the descriptor to be read") { it.projectName == "Demo App" }
        awaitState("the tree to be listed") { it.visibleNodes.isNotEmpty() }
        assertEquals(project.rootDir, viewModel.state.value.visibleNodes.first().file)
    }

    @Test
    fun selecting_a_source_file_opens_it_in_the_editor() {
        assertTrue("the template wrote no MainActivity.java", mainActivitySource.isFile)

        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }

        awaitState("the document to load") { it.document != null }
        val document = viewModel.state.value.document!!
        assertEquals(mainActivitySource, document.file)
        assertEquals(mainActivitySource.readText(), document.text)
        assertFalse("a freshly opened file is not modified", viewModel.state.value.isDocumentDirty)
    }

    @Test
    fun an_edit_is_marked_modified_and_reaches_the_file_on_disk() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the document to load") { it.document != null }

        val edited = viewModel.state.value.document!!.text + "\n// edited\n"

        // The widget echoes its own setText back; that must not count as a change.
        onMain { viewModel.onTextChanged(viewModel.state.value.document!!.text) }
        assertFalse(viewModel.state.value.isDocumentDirty)

        onMain { viewModel.onTextChanged(edited) }
        assertTrue("the edit was not noticed", viewModel.state.value.isDocumentDirty)

        onMain { viewModel.save() }
        awaitState("the save to finish") { !it.isDocumentDirty }
        assertEquals(edited, mainActivitySource.readText())
    }

    @Test
    fun opening_another_file_saves_the_one_before_it() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the document to load") { it.document != null }

        val edited = viewModel.state.value.document!!.text + "\n// switched away\n"
        onMain { viewModel.onTextChanged(edited) }

        val manifest = File(project.rootDir, "src/main/AndroidManifest.xml")
        onMain { viewModel.openDocument(manifest) }
        awaitState("the manifest to load") { it.document?.file == manifest }

        assertEquals(
            "switching files silently discarded an edit",
            edited,
            mainActivitySource.readText(),
        )
    }

    @Test
    fun building_without_the_platform_offers_to_download_it_rather_than_failing() {
        // A device that has never built anything is the state every install
        // starts in. It has to lead somewhere the user can act on.
        if (ToolchainManager(context, dispatchers).canBuild()) {
            // The platform is already installed on this device from an earlier
            // run, so there is nothing to offer. Nothing to assert either.
            return
        }

        // Deliberately without waiting first: tapping Build the instant the
        // screen opens must still work.
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.build() }

        awaitState("the platform prompt") { it.platform != null }
        val platform = viewModel.state.value.platform!!
        assertNotNull("the licence text is empty", platform.licenseText.ifBlank { null })
        assertTrue(
            "the prompt names no download",
            platform.component.archiveUrl.startsWith("https://dl.google.com/"),
        )
        assertFalse("a build was started without a platform", viewModel.state.value.build.isRunning)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
