package com.osamu.aide.ui.workspace

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentity
import com.osamu.aide.vcs.git.GitIdentityStore
import com.osamu.aide.vcs.git.GitWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * **The second half of M8's acceptance test: edit, commit, push.**
 *
 * Driven through the view model the panel is bound to, rather than through the
 * composition, because what is worth asserting is not that a button is enabled
 * -- it is that a commit exists in the object database afterwards and that the
 * receiving repository has it. A Compose test would prove the wiring and stop
 * one layer short of the claim.
 *
 * Push goes over `file://` for the reason recorded in `vcs/git/FINDINGS.md`:
 * the credential is not the half in doubt, pack generation is, and that is
 * identical over either transport.
 */
class GitViewModelTest {

    private lateinit var root: File
    private lateinit var workspaceRoot: File
    private lateinit var git: GitWorkspace
    private lateinit var identities: GitIdentityStore
    private lateinit var credentials: GitCredentialStore
    private lateinit var viewModel: GitViewModel

    /** The module the editor would open: a subfolder of the repository. */
    private lateinit var moduleDir: File
    private lateinit var repoDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "git-panel-test").apply {
            deleteRecursively()
            mkdirs()
        }
        workspaceRoot = File(root, "workspace").apply { mkdirs() }
        identities = GitIdentityStore(context).apply { clear() }
        credentials = GitCredentialStore(context).apply { clear() }
        git = GitWorkspace(context, DefaultDispatcherProvider(), identities, credentials)

        repoDir = File(workspaceRoot, "project")
        moduleDir = File(repoDir, "app")
        viewModel = GitViewModel(git, identities, workspaceRoot)
    }

    @After
    fun tearDown() {
        identities.clear()
        credentials.clear()
        root.deleteRecursively()
    }

    private fun identify() = identities.save(GitIdentity("Ada", "ada@example.invalid"))

    /** A repository with one commit, whose module is in `app/`. */
    private suspend fun seed() {
        identify()
        (git.init(repoDir) as AppResult.Success).value.use { repo ->
            File(moduleDir, "src/main").mkdirs()
            File(moduleDir, "src/main/AndroidManifest.xml")
                .writeText("""<manifest package="com.example.p"><application/></manifest>""")
            repo.stage(listOf("."))
            repo.commit("initial")
        }
    }

    private suspend fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            // A real wait: `delay` under runTest is virtual and returns at once,
            // and the view model runs on real dispatchers.
            withContext(Dispatchers.Default) { delay(25) }
        }
        throw AssertionError("timed out waiting for $what; state = ${viewModel.state.value}")
    }

    /**
     * The panel finds the repository from the *module*, which is what the
     * editor opened. A cloned Android repository is a Gradle root holding
     * `app/`, so this is the ordinary case rather than an edge one.
     */
    @Test
    fun the_panel_opens_the_repository_containing_the_project() = runTest(timeout = 2.minutes) {
        seed()

        viewModel.open(moduleDir)
        await("the repository to open") { viewModel.state.value.isRepository == true }

        assertNotNull("no branch was reported", viewModel.state.value.branch)
        assertEquals(listOf("initial"), viewModel.state.value.recent.map { it.summary })
    }

    /** A project that was created rather than cloned says so instead of failing. */
    @Test
    fun a_project_outside_a_repository_is_reported_plainly() = runTest(timeout = 2.minutes) {
        val plain = File(workspaceRoot, "not-a-repo").apply { mkdirs() }

        viewModel.open(plain)
        await("the answer") { viewModel.state.value.isRepository != null }

        assertEquals(false, viewModel.state.value.isRepository)
    }

    /**
     * A created project can be put under version control from the app.
     *
     * Found by driving the running app rather than by a test: the panel
     * correctly reported "not a git repository" and offered no way to change
     * that, so a project that was created rather than cloned could never be
     * committed to at all. `GitWorkspace.init` already existed; nothing called
     * it.
     */
    @Test
    fun a_project_with_no_repository_can_be_given_one() = runTest(timeout = 2.minutes) {
        identify()
        val plain = File(workspaceRoot, "created").apply { mkdirs() }

        viewModel.open(plain)
        await("the answer") { viewModel.state.value.isRepository != null }
        assertEquals(false, viewModel.state.value.isRepository)

        viewModel.initialise()
        await("the repository") { viewModel.state.value.isRepository == true }

        assertTrue("no .git directory was created", File(plain, ".git").isDirectory)
        assertEquals("Repository created.", viewModel.state.value.notice)

        // And it is a working repository, not just a directory: a commit lands.
        File(plain, "a.txt").writeText("first\n")
        viewModel.refresh()
        await("the file") { viewModel.state.value.status.untracked.isNotEmpty() }
        viewModel.stage(listOf("a.txt"))
        await("staging") { viewModel.state.value.status.staged.isNotEmpty() }
        viewModel.setMessage("first commit")
        viewModel.commit()
        await("the commit") { viewModel.state.value.recent.firstOrNull()?.summary == "first commit" }
    }

    @Test
    fun editing_staging_and_committing_writes_a_commit() = runTest(timeout = 2.minutes) {
        seed()
        viewModel.open(moduleDir)
        await("the repository to open") { viewModel.state.value.isRepository == true }

        File(moduleDir, "src/main/java").mkdirs()
        File(moduleDir, "src/main/java/Main.java").writeText("class Main {}\n")
        viewModel.refresh()
        await("the new file to show up") { viewModel.state.value.status.untracked.isNotEmpty() }

        val path = viewModel.state.value.status.untracked.single()
        assertEquals("app/src/main/java/Main.java", path)

        viewModel.stage(listOf(path))
        await("the file to be staged") { viewModel.state.value.status.staged.contains(path) }

        viewModel.setMessage("add Main")
        viewModel.commit()
        await("the commit") { viewModel.state.value.recent.firstOrNull()?.summary == "add Main" }

        val state = viewModel.state.value
        assertTrue("the tree is not clean after committing", state.status.isClean)
        assertEquals("the message was not cleared", "", state.message)
        assertEquals("Ada", state.recent.first().authorName)

        // Read back from the object database rather than trusted from the model.
        FileRepositoryBuilder().setGitDir(File(repoDir, ".git")).build().use { raw ->
            RevWalk(raw).use { walk ->
                assertEquals(
                    "add Main",
                    walk.parseCommit(raw.resolve(Constants.HEAD)).fullMessage.trim(),
                )
            }
        }
    }

    /**
     * Unstaging puts a file back without touching it on disk.
     *
     * Worth its own test because the failure mode is silent and destructive:
     * an "unstage" implemented as a hard reset would take the user's edit with
     * it.
     */
    @Test
    fun unstaging_leaves_the_edit_alone() = runTest(timeout = 2.minutes) {
        seed()
        viewModel.open(moduleDir)
        await("the repository to open") { viewModel.state.value.isRepository == true }

        val file = File(moduleDir, "notes.txt").apply { writeText("kept\n") }
        viewModel.refresh()
        await("the file") { viewModel.state.value.status.untracked.isNotEmpty() }

        viewModel.stage(listOf("app/notes.txt"))
        await("staging") { viewModel.state.value.status.staged.isNotEmpty() }
        viewModel.unstage(listOf("app/notes.txt"))
        await("unstaging") { viewModel.state.value.status.staged.isEmpty() }

        assertEquals("kept\n", file.readText())
    }

    /**
     * No identity means no commit, and the panel knows before the button is
     * tapped -- so it can say why rather than showing a failure afterwards.
     */
    @Test
    fun committing_without_an_identity_is_blocked_and_reported() = runTest(timeout = 2.minutes) {
        seed()
        identities.clear()
        viewModel.open(moduleDir)
        await("the repository to open") { viewModel.state.value.isRepository == true }

        assertFalse("the panel thinks it can commit", viewModel.state.value.hasIdentity)

        File(moduleDir, "x.txt").writeText("x\n")
        viewModel.refresh()
        await("the file") { viewModel.state.value.status.untracked.isNotEmpty() }
        viewModel.stage(listOf("app/x.txt"))
        await("staging") { viewModel.state.value.status.staged.isNotEmpty() }

        viewModel.setMessage("should not land")
        viewModel.commit()
        await("the refusal") { viewModel.state.value.errorMessage != null }

        assertTrue(
            "unhelpful: ${viewModel.state.value.errorMessage}",
            "identity" in viewModel.state.value.errorMessage.orEmpty(),
        )
        assertEquals(listOf("initial"), viewModel.state.value.recent.map { it.summary })
    }

    /**
     * The panel can show what actually changed, not just which files did.
     *
     * Loaded on demand, so this asserts the round trip a tap makes: ask for one
     * path, get that path's diff back, and be able to put it away.
     */
    @Test
    fun a_changed_file_can_be_looked_at() = runTest(timeout = 2.minutes) {
        seed()
        viewModel.open(moduleDir)
        await("the repository to open") { viewModel.state.value.isRepository == true }

        val file = File(moduleDir, "notes.txt").apply { writeText("before\n") }
        viewModel.refresh()
        await("the file") { viewModel.state.value.status.untracked.isNotEmpty() }
        viewModel.stage(listOf("app/notes.txt"))
        await("staging") { viewModel.state.value.status.staged.isNotEmpty() }
        viewModel.commit()
        // No message set, so that commit is refused -- which is fine, the point
        // is the file is tracked. Commit it properly.
        viewModel.setMessage("add notes")
        viewModel.commit()
        await("the commit") { viewModel.state.value.recent.firstOrNull()?.summary == "add notes" }

        file.writeText("after\n")
        viewModel.refresh()
        await("the edit") { viewModel.state.value.status.modified.isNotEmpty() }

        viewModel.showDiff("app/notes.txt", staged = false)
        await("the diff") { viewModel.state.value.diff != null }

        val diff = viewModel.state.value.diff!!
        assertEquals("app/notes.txt", diff.path)
        assertEquals(false, diff.staged)
        assertTrue("the old line is missing:\n${diff.text}", "-before" in diff.text)
        assertTrue("the new line is missing:\n${diff.text}", "+after" in diff.text)

        viewModel.dismissDiff()
        assertEquals(null, viewModel.state.value.diff)
    }

    /** **"push" in the acceptance test**, asserted on the receiving repository. */
    @Test
    fun pushing_lands_the_commit_on_the_remote() = runTest(timeout = 2.minutes) {
        seed()
        val bare = File(root, "remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        Git.open(repoDir).use {
            it.remoteAdd().setName("origin").setUri(URIish(bare.toURI().toString())).call()
        }

        viewModel.open(moduleDir)
        await("the repository to open") { viewModel.state.value.isRepository == true }

        File(moduleDir, "pushed.txt").writeText("this travelled in a pack\n")
        viewModel.refresh()
        await("the file") { viewModel.state.value.status.untracked.isNotEmpty() }
        viewModel.stage(listOf("app/pushed.txt"))
        await("staging") { viewModel.state.value.status.staged.isNotEmpty() }
        viewModel.setMessage("pushed")
        viewModel.commit()
        await("the commit") { viewModel.state.value.recent.firstOrNull()?.summary == "pushed" }

        viewModel.push()
        await("the push") { viewModel.state.value.notice != null || viewModel.state.value.errorMessage != null }

        assertEquals("push failed: ${viewModel.state.value.errorMessage}", "Pushed.", viewModel.state.value.notice)

        val committed = viewModel.state.value.recent.first().id
        FileRepositoryBuilder().setGitDir(bare).build().use { remote ->
            val id = remote.resolve(committed)
            assertNotNull("the remote does not have the pushed commit", id)
            RevWalk(remote).use { walk ->
                assertEquals("pushed", walk.parseCommit(id).fullMessage.trim())
            }
        }
    }
}
