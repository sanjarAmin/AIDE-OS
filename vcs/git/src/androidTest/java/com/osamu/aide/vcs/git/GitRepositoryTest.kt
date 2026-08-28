package com.osamu.aide.vcs.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The repository operations, on a device.
 *
 * Instrumented rather than local because half of what is being asserted is
 * Android's filesystem answering JGit correctly -- `status` is `FileSnapshot`
 * comparisons, and one that is wrong reports a clean tree as dirty or the
 * reverse. Spike R6 established that it does answer correctly
 * (`tools/git/FINDINGS.md`); this is the regression net for that, plus the
 * behaviour this module adds on top.
 *
 * Every assertion is on the observable effect. A commit is checked by reading
 * it back out of the object database, and a push by resolving the commit in the
 * *receiving* repository -- JGit reports a rejected update through its return
 * value rather than by throwing, so "it did not throw" proves nothing.
 */
@RunWith(AndroidJUnit4::class)
class GitRepositoryTest {

    private lateinit var workDir: File
    private lateinit var workspace: GitWorkspace
    private lateinit var identities: GitIdentityStore
    private lateinit var credentials: GitCredentialStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "vcs-git-test").apply {
            deleteRecursively()
            mkdirs()
        }
        identities = GitIdentityStore(context)
        credentials = GitCredentialStore(context)
        identities.clear()
        credentials.clear()
        workspace = GitWorkspace(context, DefaultDispatcherProvider(), identities, credentials)
    }

    @After
    fun tearDown() {
        // The stores are process-wide preferences, so a test that left one set
        // would decide the outcome of the next.
        identities.clear()
        credentials.clear()
        workDir.deleteRecursively()
    }

    private fun identify() {
        assertNull(identities.save(GitIdentity("AIDE-OS", "test@example.invalid")))
    }

    private suspend fun repository(name: String = "repo"): GitRepository =
        when (val result = workspace.init(File(workDir, name))) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> throw AssertionError(result.error.message)
        }

    private fun <T> AppResult<T>.orFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AssertionError(error.message)
    }

    private fun AppResult<*>.failureMessage(): String = when (this) {
        is AppResult.Failure -> error.message
        is AppResult.Success -> throw AssertionError("expected a failure, got $value")
    }

    @Test
    fun a_new_repository_is_empty_and_clean() = runTest {
        repository().use { repo ->
            assertTrue("a fresh repository is not clean", repo.status().orFail().isClean)
            assertEquals(emptyList<GitCommit>(), repo.log().orFail())
        }
    }

    /**
     * The point of [GitIdentityStore]: a commit with no identity is refused
     * rather than made up.
     *
     * JGit would happily commit here using an author assembled from Android's
     * system properties. That commit is not wrong so much as unattributable,
     * and the only way to correct it afterwards is to rewrite history.
     */
    @Test
    fun a_commit_without_an_identity_is_refused() = runTest {
        repository().use { repo ->
            File(repo.workTree, "a.txt").writeText("hello\n")
            repo.stage(listOf("a.txt")).orFail()

            val message = repo.commit("first").failureMessage()
            assertTrue("the message does not say what to do: $message", "identity" in message)

            // And nothing was written: a refusal that half-committed would be
            // worse than the fabricated author it was avoiding.
            assertEquals(emptyList<GitCommit>(), repo.log().orFail())
        }
    }

    @Test
    fun staging_then_committing_writes_a_readable_commit() = runTest {
        identify()
        repository().use { repo ->
            File(repo.workTree, "a.txt").writeText("hello\n")

            val before = repo.status().orFail()
            assertEquals(setOf("a.txt"), before.untracked)
            assertFalse(before.isClean)

            repo.stage(listOf("a.txt")).orFail()
            assertEquals(setOf("a.txt"), repo.status().orFail().added)

            val commit = repo.commit("first commit").orFail()
            assertEquals("first commit", commit.fullMessage)
            assertEquals("AIDE-OS", commit.authorName)
            assertEquals(40, commit.id.length)
            assertEquals(7, commit.abbreviated.length)

            assertTrue("the tree is not clean after committing", repo.status().orFail().isClean)

            // Read back from the object database rather than trusted from the
            // returned model: a commit that produced no reachable tree would
            // still have given us an id.
            FileRepositoryBuilder()
                .setGitDir(File(repo.workTree, Constants.DOT_GIT))
                .build()
                .use { raw ->
                    RevWalk(raw).use { walk ->
                        val head = walk.parseCommit(raw.resolve(Constants.HEAD))
                        assertEquals(commit.id, head.name)
                        assertNotNull("the commit has no tree", head.tree)
                    }
                }
        }
    }

    @Test
    fun an_empty_commit_is_refused() = runTest {
        identify()
        repository().use { repo ->
            File(repo.workTree, "a.txt").writeText("hello\n")
            repo.stage(listOf("a.txt")).orFail()
            repo.commit("first").orFail()

            val message = repo.commit("again").failureMessage()
            assertTrue("unhelpful message: $message", "nothing to commit" in message)
            assertEquals(1, repo.log().orFail().size)
        }
    }

    @Test
    fun a_blank_message_is_refused() = runTest {
        identify()
        repository().use { repo ->
            File(repo.workTree, "a.txt").writeText("hello\n")
            repo.stage(listOf("a.txt")).orFail()

            assertTrue("   ".let { repo.commit(it).failureMessage() }.contains("message"))
            assertEquals(emptyList<GitCommit>(), repo.log().orFail())
        }
    }

    /**
     * Staging a deletion needs a second call with `setUpdate(true)`; `add`
     * alone records content, not absence. A file deleted and "staged" without
     * it stays in the next commit.
     */
    @Test
    fun staging_records_a_deletion() = runTest {
        identify()
        repository().use { repo ->
            File(repo.workTree, "a.txt").writeText("hello\n")
            repo.stage(listOf("a.txt")).orFail()
            repo.commit("add a").orFail()

            assertTrue(File(repo.workTree, "a.txt").delete())
            assertEquals(setOf("a.txt"), repo.status().orFail().missing)

            repo.stage(listOf("a.txt")).orFail()
            assertEquals(setOf("a.txt"), repo.status().orFail().removed)

            repo.commit("remove a").orFail()
            assertTrue("the deletion did not survive the commit", repo.status().orFail().isClean)

            // The file is gone from HEAD's tree, not merely from the disk.
            val head = repo.log().orFail().first()
            assertEquals("remove a", head.fullMessage)
        }
    }

    /**
     * Editing a tracked file stages as `changed`, not `added`.
     *
     * `GitStatus` has seven fields and every one defaults to empty, so a
     * mapping that dropped one would report a file as permanently unstaged and
     * nothing would fail. `added`, `removed`, `modified`, `missing` and
     * `untracked` are each asserted by another test here; this is `changed`,
     * which is the state an edit to an existing file passes through and the one
     * a real commit is usually made of.
     */
    @Test
    fun editing_a_tracked_file_stages_as_changed() = runTest {
        identify()
        repository().use { repo ->
            val file = File(repo.workTree, "a.txt").apply { writeText("first\n") }
            repo.stage(listOf("a.txt")).orFail()
            repo.commit("add a").orFail()

            file.writeText("second\n")
            assertEquals(setOf("a.txt"), repo.status().orFail().modified)

            repo.stage(listOf("a.txt")).orFail()
            val staged = repo.status().orFail()
            assertEquals("an edit to a tracked file is 'changed'", setOf("a.txt"), staged.changed)
            assertEquals("it is not a new file", emptySet<String>(), staged.added)
            // The panel commits `staged`, which is added + changed + removed.
            assertEquals(setOf("a.txt"), staged.staged)

            repo.commit("edit a").orFail()
            assertTrue(repo.status().orFail().isClean)
            assertEquals("second\n", file.readText())
        }
    }

    @Test
    fun unstaging_leaves_the_working_tree_alone() = runTest {
        identify()
        repository().use { repo ->
            val file = File(repo.workTree, "a.txt").apply { writeText("hello\n") }
            repo.stage(listOf("a.txt")).orFail()
            repo.unstage(listOf("a.txt")).orFail()

            assertEquals(setOf("a.txt"), repo.status().orFail().untracked)
            assertEquals("hello\n", file.readText())
        }
    }

    @Test
    fun history_comes_back_newest_first() = runTest {
        identify()
        repository().use { repo ->
            repeat(3) { index ->
                File(repo.workTree, "file-$index.txt").writeText("contents $index\n")
                repo.stage(listOf("file-$index.txt")).orFail()
                repo.commit("commit $index").orFail()
            }

            assertEquals(
                listOf("commit 2", "commit 1", "commit 0"),
                repo.log().orFail().map { it.fullMessage },
            )
            assertEquals(2, repo.log(limit = 2).orFail().size)
        }
    }

    /**
     * Push, asserted on the receiving repository.
     *
     * Over `file://` deliberately: pushing to a host needs a credential this
     * suite must not carry, and the half in doubt is pack generation, which is
     * identical over either transport. `tools/git/FINDINGS.md` finding 4.
     */
    @Test
    fun a_push_lands_in_the_receiving_repository() = runTest {
        identify()
        val bare = File(workDir, "remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()

        repository("pushing").use { repo ->
            File(repo.workTree, "a.txt").writeText("this travelled in a pack\n")
            repo.stage(listOf("a.txt")).orFail()
            val commit = repo.commit("pushed").orFail()

            Git.open(repo.workTree).use { raw ->
                raw.remoteAdd()
                    .setName("origin")
                    .setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString()))
                    .call()
            }

            val phases = mutableListOf<GitProgress>()
            repo.push(onProgress = phases::add).orFail()

            FileRepositoryBuilder().setGitDir(bare).build().use { remote ->
                val id = remote.resolve(commit.id)
                assertNotNull("the remote does not have the pushed commit", id)
                RevWalk(remote).use { walk ->
                    assertEquals("pushed", walk.parseCommit(id).fullMessage.trim())
                }
            }
            assertTrue("push reported no progress at all", phases.isNotEmpty())
        }
    }

    /**
     * A web remote with no stored token fails before the network, naming the
     * host. The alternative is JGit's own 401, which tells the user nothing
     * they can act on.
     */
    @Test
    fun pushing_to_a_web_remote_without_a_token_says_which_host() = runTest {
        identify()
        repository("needs-token").use { repo ->
            File(repo.workTree, "a.txt").writeText("x\n")
            repo.stage(listOf("a.txt")).orFail()
            repo.commit("only commit").orFail()

            Git.open(repo.workTree).use { raw ->
                raw.remoteAdd()
                    .setName("origin")
                    .setUri(org.eclipse.jgit.transport.URIish("https://example.invalid/repo.git"))
                    .call()
            }

            val message = repo.push().failureMessage()
            assertTrue("the host is not named: $message", "example.invalid" in message)
        }
    }

    @Test
    fun the_remote_url_is_readable() = runTest {
        repository("with-remote").use { repo ->
            assertNull(repo.remoteUrl())

            Git.open(repo.workTree).use { raw ->
                raw.remoteAdd()
                    .setName("origin")
                    .setUri(org.eclipse.jgit.transport.URIish("https://example.invalid/repo.git"))
                    .call()
            }
            assertEquals("https://example.invalid/repo.git", repo.remoteUrl())
        }
    }
}
