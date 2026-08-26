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
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import com.osamu.aide.engine.fast.KotlinToolchainProvider
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
import org.junit.Assume.assumeTrue
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
        val projectDependencies = ProjectDependencies(
            DependencyResolver(File(context.cacheDir, "maven"), dispatchers),
        )
        stagePlatformJar()
        viewModel = WorkspaceViewModel(
            dispatchers = dispatchers,
            projects = repository,
            documents = DocumentStore(dispatchers),
            builder = ProjectBuilder(
                toolchain = toolchain,
                platforms = AndroidPlatformProvider(context, dispatchers),
                runner = NativeToolRunner(NativeToolchain.from(context), dispatchers),
                dependencies = projectDependencies,
                // Points at a directory with no toolchain in it, so
                // compiler() returns null -- which is what a device without
                // the 54 MB download has, and the case this test is about.
                kotlin = KotlinCompilerSource(
                    KotlinToolchainProvider(context),
                    File(context.cacheDir, "kotlin-host-test"),
                ),
                dispatchers = dispatchers,
                outputRoot = File(context.cacheDir, "builds-test"),
            ),
            toolchain = toolchain,
            installer = ApkInstaller(context, dispatchers),
            languageServices = LanguageServices(
                toolchain = toolchain,
                dispatchers = dispatchers,
                buildOutputRoot = File(context.cacheDir, "builds-test"),
            ),
            dependencies = projectDependencies,
            languages = EditorLanguages(context),
        )
        Unit
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    /**
     * Puts an `android.jar` where [ToolchainManager] looks for one.
     *
     * Language intelligence is disabled without it, so a test asserting that
     * diagnostics appear would otherwise pass by asserting nothing. Copied from
     * the same staged asset `:engine:fast`'s tests use rather than downloaded;
     * see that module's FINDINGS on what is and is not in git.
     */
    private fun stagePlatformJar() {
        val target = File(
            context.filesDir,
            "toolchains/platforms-android-36/android.jar",
        )
        if (target.isFile) return
        target.parentFile?.mkdirs()
        runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("android.jar")
                .use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    private val hasPlatform: Boolean
        get() = File(context.filesDir, "toolchains/platforms-android-36/android.jar").isFile

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

    private val manifest: File
        get() = File(project.rootDir, "src/main/AndroidManifest.xml")

    /**
     * The wiring this milestone is actually about: typing produces diagnostics.
     *
     * Everything between the keystroke and the gutter is in scope here --
     * the debounce, the language service, the state -- because each piece is
     * tested in isolation elsewhere and none of that proves they are connected.
     * The bug this catches is the one that unit tests never do: a service that
     * works perfectly and is never called.
     */
    @Test
    fun typing_a_broken_line_puts_a_diagnostic_in_the_state() {
        assumeTrue("no android.jar staged; language services are disabled", hasPlatform)

        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the document to load") { it.active != null }

        val broken = mainActivitySource.readText()
            .replace("setContentView(text);", "setContentView(text); int x = notAThing;")
        onMain { viewModel.onTextChanged(broken) }

        awaitState("analysis to report the undefined symbol") { state ->
            state.analysis.file == mainActivitySource &&
                state.analysis.diagnostics.any { "notAThing" in it.message }
        }

        // And it must reach the gutter, which reads editorDiagnostics rather
        // than the analysis directly.
        val shown = viewModel.state.value.editorDiagnostics
        assertTrue("the diagnostic did not reach the gutter", shown.any { "notAThing" in it.message })
    }

    /**
     * Fixing the line has to clear it again, or the gutter lies.
     *
     * Asserted against the specific message rather than an empty list, because
     * a project that has never been built legitimately has one other error:
     * `R` is generated by aapt2, so until a build has run there is no `R.java`
     * for the template's `R.string.greeting` to resolve against. Demanding
     * silence here would be demanding the analysis lie about that.
     */
    @Test
    fun fixing_the_line_clears_the_diagnostic() {
        assumeTrue("no android.jar staged; language services are disabled", hasPlatform)

        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the document to load") { it.active != null }

        val original = mainActivitySource.readText()
        val broken = original.replace("setContentView(text);", "setContentView(text); int x = notAThing;")

        onMain { viewModel.onTextChanged(broken) }
        awaitState("the error to appear") { state ->
            state.analysis.diagnostics.any { "notAThing" in it.message }
        }

        onMain { viewModel.onTextChanged(original) }
        awaitState("the error to clear") { state ->
            state.analysis.file == mainActivitySource &&
                state.analysis.diagnostics.none { "notAThing" in it.message }
        }
        assertTrue(
            "the gutter still shows the fixed error",
            viewModel.state.value.editorDiagnostics.none { "notAThing" in it.message },
        )
    }

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

        awaitState("the document to load") { it.active != null }
        val active = viewModel.state.value.active!!
        assertEquals(mainActivitySource, active.file)
        assertEquals(mainActivitySource.readText(), active.document.text)
        assertFalse("a freshly opened file is not modified", active.isDirty)
    }

    @Test
    fun an_edit_is_marked_modified_and_reaches_the_file_on_disk() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the document to load") { it.active != null }

        val edited = viewModel.state.value.active!!.document.text + "\n// edited\n"

        // The widget echoes its own setText back; that must not count as a change.
        onMain { viewModel.onTextChanged(viewModel.state.value.active!!.document.text) }
        assertFalse(viewModel.state.value.isDocumentDirty)

        onMain { viewModel.onTextChanged(edited) }
        assertTrue("the edit was not noticed", viewModel.state.value.isDocumentDirty)

        onMain { viewModel.save() }
        awaitState("the save to finish") { !it.isDocumentDirty }
        assertEquals(edited, mainActivitySource.readText())
    }

    @Test
    fun opening_a_second_file_adds_a_tab_and_leaves_the_first_open() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the first document") { it.active?.file == mainActivitySource }

        onMain { viewModel.openDocument(manifest) }
        awaitState("the second document") { it.active?.file == manifest }

        val state = viewModel.state.value
        assertEquals(2, state.openFiles.size)
        assertEquals(
            listOf(mainActivitySource, manifest),
            state.openFiles.map { it.file },
        )
    }

    @Test
    fun reopening_a_file_that_is_already_open_activates_its_tab() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the first document") { it.active?.file == mainActivitySource }
        onMain { viewModel.openDocument(manifest) }
        awaitState("the second document") { it.active?.file == manifest }

        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the first tab to come forward") { it.active?.file == mainActivitySource }

        assertEquals(
            "a duplicate tab was opened",
            2,
            viewModel.state.value.openFiles.size,
        )
    }

    @Test
    fun each_tab_tracks_its_own_unsaved_changes() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the first document") { it.active?.file == mainActivitySource }

        val edited = viewModel.state.value.active!!.document.text + "\n// edited\n"
        onMain { viewModel.onTextChanged(edited) }

        onMain { viewModel.openDocument(manifest) }
        awaitState("the second document") { it.active?.file == manifest }

        val state = viewModel.state.value
        assertFalse("the manifest was not edited", state.isDocumentDirty)
        assertTrue(
            "the first tab lost its unsaved change",
            state.openFiles.first { it.file == mainActivitySource }.isDirty,
        )
        // Still only in the buffer -- switching tabs is not a save.
        assertFalse(mainActivitySource.readText().contains("// edited"))
    }

    @Test
    fun closing_a_tab_saves_it_and_falls_back_to_its_neighbour() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the first document") { it.active?.file == mainActivitySource }
        onMain { viewModel.openDocument(manifest) }
        awaitState("the second document") { it.active?.file == manifest }

        val edited = viewModel.state.value.active!!.document.text + "\n<!-- edited -->\n"
        onMain { viewModel.onTextChanged(edited) }

        onMain { viewModel.closeDocument(manifest) }
        awaitState("the tab to close") { it.openFiles.size == 1 }

        assertEquals(mainActivitySource, viewModel.state.value.activeFile)
        assertEquals("closing a tab discarded its edit", edited, manifest.readText())
    }

    @Test
    fun a_build_saves_every_modified_tab_not_just_the_active_one() {
        onMain { viewModel.open(project.rootDir) }
        onMain { viewModel.openDocument(mainActivitySource) }
        awaitState("the first document") { it.active?.file == mainActivitySource }

        val edited = viewModel.state.value.active!!.document.text + "\n// built with this\n"
        onMain { viewModel.onTextChanged(edited) }

        onMain { viewModel.openDocument(manifest) }
        awaitState("the second document") { it.active?.file == manifest }

        // Build refuses for want of a platform, or runs -- either way it saves
        // first, because the compiler reads the disk and not the buffers.
        onMain { viewModel.build() }
        awaitState("the background tab to be written") {
            mainActivitySource.readText() == edited
        }
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
