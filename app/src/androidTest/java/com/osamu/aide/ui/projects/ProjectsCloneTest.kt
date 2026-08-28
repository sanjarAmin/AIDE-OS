package com.osamu.aide.ui.projects

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.FileProjectRepository
import com.osamu.aide.core.fs.ProjectAdoption
import com.osamu.aide.core.fs.ProjectImporter
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentity
import com.osamu.aide.vcs.git.GitIdentityStore
import com.osamu.aide.vcs.git.GitWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Cloning a repository and having it turn up as a project.
 *
 * The two halves are separate operations and the second is the one that can
 * surprise: a repository clones perfectly well and then holds no Android
 * module, or two. So this asserts on the **projects list**, which is what the
 * user sees, rather than on the clone returning.
 *
 * Driven over `file://` so it is deterministic and needs no network. The
 * transport is not what is under test here -- spike R6 and `GitWorkspaceTest`
 * cover that -- the wiring is.
 */
class ProjectsCloneTest {

    private lateinit var root: File
    private lateinit var workspaceRoot: File
    private lateinit var git: GitWorkspace
    private lateinit var identities: GitIdentityStore
    private lateinit var credentials: GitCredentialStore
    private lateinit var viewModel: ProjectsViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "clone-flow-test").apply {
            deleteRecursively()
            mkdirs()
        }
        workspaceRoot = File(root, "workspace").apply { mkdirs() }
        identities = GitIdentityStore(context).apply { clear() }
        credentials = GitCredentialStore(context).apply { clear() }

        val dispatchers = DefaultDispatcherProvider()
        git = GitWorkspace(context, dispatchers, identities, credentials)
        viewModel = ProjectsViewModel(
            repository = FileProjectRepository(workspaceRoot, dispatchers),
            importer = ProjectImporter(context, workspaceRoot, dispatchers),
            git = git,
            adoption = ProjectAdoption(dispatchers),
            workspaceRoot = workspaceRoot,
        )
    }

    @After
    fun tearDown() {
        identities.clear()
        credentials.clear()
        root.deleteRecursively()
    }

    /**
     * Builds a repository whose module sits in `app/`, which is the shape every
     * real Android repository has and the one the adoption rule exists for.
     */
    private suspend fun sourceRepository(name: String, module: String? = "app"): File {
        val dir = File(root, name)
        identities.save(GitIdentity("AIDE-OS", "test@example.invalid"))
        (git.init(dir) as AppResult.Success).value.use {
            val base = if (module == null) dir else File(dir, module)
            File(base, "src/main/java/com/example/cloned").mkdirs()
            File(base, "src/main/AndroidManifest.xml").writeText(
                """<manifest package="com.example.cloned"><application/></manifest>""",
            )
            File(base, "src/main/java/com/example/cloned/Main.java")
                .writeText("package com.example.cloned;\npublic class Main {}\n")

            it.stage(listOf("."))
            it.commit("initial")
        }
        return dir
    }

    private suspend fun projects() =
        (
            FileProjectRepository(workspaceRoot, DefaultDispatcherProvider()).listProjects()
                as AppResult.Success
            ).value

    @Test
    fun a_cloned_repository_becomes_a_project() = runTest(timeout = 3.minutes) {
        val source = sourceRepository("upstream.git")

        viewModel.cloneRepository(source.toURI().toString())
        awaitUntil("the clone to produce a project") { projects().isNotEmpty() }

        val listed = projects()
        assertEquals(1, listed.size)
        val project = listed.single()
        assertEquals("upstream", project.name)
        // Rooted at the module, not at the clone: `app/` is what the editor and
        // the build engine open.
        assertEquals("app", project.rootDir.name)
        assertEquals("com.example.cloned", project.applicationId)
        assertEquals(SourceLanguage.JAVA, project.language)

        // And version control still means the whole clone.
        assertEquals(
            File(workspaceRoot, "upstream").canonicalFile,
            git.enclosingRepository(project.rootDir, ceiling = workspaceRoot),
        )
        assertNull("a status was left showing", viewModel.state.value.cloneStatus)
    }

    /** A repository whose module is at the top level is adopted as itself. */
    @Test
    fun a_repository_with_no_subfolder_is_adopted_at_its_root() = runTest(timeout = 3.minutes) {
        val source = sourceRepository("flat", module = null)

        viewModel.cloneRepository(source.toURI().toString())
        awaitUntil("the clone to produce a project") { projects().isNotEmpty() }

        assertEquals("flat", projects().single().rootDir.name)
    }

    /**
     * A clone that holds no Android module is reported, and **kept**.
     *
     * The user paid for those bytes over a mobile connection. Deleting them
     * because we could not name a module is the worse of the two outcomes, and
     * the message says what actually happened rather than "clone failed".
     */
    @Test
    fun a_repository_with_no_module_is_reported_but_not_deleted() = runTest(timeout = 3.minutes) {
        val source = File(root, "no-module")
        identities.save(GitIdentity("AIDE-OS", "test@example.invalid"))
        (git.init(source) as AppResult.Success).value.use {
            File(source, "README.md").writeText("just a readme\n")
            it.stage(listOf("."))
            it.commit("initial")
        }

        viewModel.cloneRepository(source.toURI().toString())
        awaitUntil("the failure to be reported") { viewModel.state.value.errorMessage != null }

        assertTrue("nothing should have been listed", projects().isEmpty())

        val message = viewModel.state.value.errorMessage
        assertNotNull("the failure was silent", message)
        assertTrue("does not say the clone succeeded: $message", message!!.startsWith("Cloned,"))

        val clone = File(workspaceRoot, "no-module")
        assertTrue("the clone was thrown away", File(clone, "README.md").isFile)
    }

    /** The name comes from the URL, the way every git client does it. */
    @Test
    fun the_directory_is_named_after_the_url() = runTest(timeout = 3.minutes) {
        val source = sourceRepository("Fancy-Name.git")

        viewModel.cloneRepository(source.toURI().toString())
        awaitUntil("the clone to produce a project") { projects().isNotEmpty() }

        assertTrue(File(workspaceRoot, "Fancy-Name").isDirectory)
        assertEquals("Fancy-Name", projects().single().name)
    }

    /**
     * Polls until [condition] holds.
     *
     * `runTest` cannot advance this work: the clone runs on `viewModelScope`,
     * whose dispatcher is the real Android main thread, and JGit blocks a real
     * IO thread inside it. Neither is a `TestDispatcher`, so virtual time does
     * nothing.
     *
     * Waiting for the *outcome* rather than for the view model to look idle,
     * which was the first version of this and was a race it lost every time:
     * `viewModelScope.launch` only posts to the main looper, so a state read
     * immediately afterwards is the state from before the clone started, and
     * every assertion ran against an empty workspace.
     */
    private suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            // A real sleep, not `delay`: under `runTest` delay is virtual and
            // returns at once, so this spun hot for a minute and tripped
            // runTest's own timeout instead of waiting for anything.
            withContext(Dispatchers.Default) { delay(50) }
        }
        throw AssertionError(
            "timed out waiting for $what\n" +
                "  state: ${viewModel.state.value}\n" +
                "  workspace: ${workspaceRoot.listFiles()?.map { it.name }}\n" +
                "  clone contents: ${
                    workspaceRoot.listFiles()?.firstOrNull()?.listFiles()?.map { it.name }
                }",
        )
    }
}
