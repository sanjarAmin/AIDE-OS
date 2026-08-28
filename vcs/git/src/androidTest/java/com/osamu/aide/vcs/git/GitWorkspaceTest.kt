package com.osamu.aide.vcs.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opening, initialising and cloning.
 *
 * The clone assertions here are about the *failure* paths, which are the ones
 * that can be made deterministic: a successful clone is a network operation
 * measured in minutes (`tools/git/FINDINGS.md` finding 5) and belongs in the
 * spike, not in a suite that runs on every change.
 */
@RunWith(AndroidJUnit4::class)
class GitWorkspaceTest {

    private lateinit var workDir: File
    private lateinit var workspace: GitWorkspace
    private lateinit var identities: GitIdentityStore
    private lateinit var credentials: GitCredentialStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "vcs-workspace-test").apply {
            deleteRecursively()
            mkdirs()
        }
        identities = GitIdentityStore(context).apply { clear() }
        credentials = GitCredentialStore(context).apply { clear() }
        workspace = GitWorkspace(context, DefaultDispatcherProvider(), identities, credentials)
    }

    @After
    fun tearDown() {
        identities.clear()
        credentials.clear()
        workDir.deleteRecursively()
    }

    private companion object {
        const val REMOTE = "https://github.com/octocat/Spoon-Knife.git"
    }

    private fun AppResult<*>.failureMessage(): String = when (this) {
        is AppResult.Failure -> error.message
        is AppResult.Success -> throw AssertionError("expected a failure, got $value")
    }

    @Test
    fun init_creates_a_repository_and_refuses_to_do_it_twice() = runTest {
        val directory = File(workDir, "fresh")
        assertFalse(workspace.isRepository(directory))

        (workspace.init(directory) as AppResult.Success).value.close()

        assertTrue(workspace.isRepository(directory))
        assertTrue("re-initialising was allowed", "already" in workspace.init(directory).failureMessage())
    }

    @Test
    fun opening_something_that_is_not_a_repository_says_so() = runTest {
        val directory = File(workDir, "plain").apply { mkdirs() }
        File(directory, "a.txt").writeText("not a repository\n")

        assertTrue("not a git repository" in workspace.open(directory).failureMessage())
    }

    /**
     * JGit's builder walks *up* looking for a `.git`, which on a device means
     * opening the enclosing project because one workspace folder happened to
     * sit inside another. The caller knows which root it means.
     */
    @Test
    fun opening_a_directory_inside_a_repository_does_not_open_the_repository() = runTest {
        val outer = File(workDir, "outer")
        (workspace.init(outer) as AppResult.Success).value.close()
        val inner = File(outer, "module").apply { mkdirs() }

        assertTrue("not a git repository" in workspace.open(inner).failureMessage())
    }

    @Test
    fun cloning_into_a_non_empty_directory_is_refused_before_the_network() = runTest {
        val directory = File(workDir, "occupied").apply { mkdirs() }
        File(directory, "existing.txt").writeText("mine\n")

        val message = workspace.clone("https://example.invalid/r.git", directory).failureMessage()
        assertTrue("unhelpful: $message", "not empty" in message)
        // Refused *before* the network means the existing file is still there.
        assertTrue("the directory was touched anyway", File(directory, "existing.txt").isFile)
    }

    @Test
    fun a_url_with_no_host_is_refused() = runTest {
        val message = workspace.clone("this is not a url", File(workDir, "nope")).failureMessage()
        assertTrue("unhelpful: $message", "repository URL" in message)
    }

    /**
     * A clone that fails reports it in the caller's words, and leaves nothing.
     *
     * **The directory is pre-created deliberately.** JGit removes a directory
     * it made itself, so a test that lets it do that asserts only that JGit
     * works. Handed one that already exists, JGit empties it and leaves it
     * standing -- and that is the case a file picker produces, so it is the
     * one worth pinning.
     */
    @Test
    fun a_failed_clone_leaves_nothing_behind() = runTest {
        val directory = File(workDir, "doomed").apply { mkdirs() }

        val message = workspace.clone("https://no.such.host.invalid/r.git", directory)
            .failureMessage()

        assertTrue("unhelpful: $message", message.startsWith("Could not clone"))
        assertFalse("something was left on disk", directory.exists())
    }

    /**
     * A cancelled clone leaves nothing behind.
     *
     * This is the path JGit does *not* clean up: told to stop by the monitor,
     * it abandons the tree where it stands. A half-written clone looks like a
     * project, opens like a project, and is missing objects it only complains
     * about later.
     *
     * Driven over `file://` and cancelled from inside the progress callback,
     * which runs on JGit's own thread between work units -- so the cancel
     * arrives exactly where a user's would, and the test needs no network and
     * no sleeping.
     */
    @Test
    fun a_cancelled_clone_leaves_nothing_behind() = runTest {
        // Pre-created for the same reason as the failure test above: JGit
        // cleans up only what it created itself.
        val target = File(workDir, "cancelled").apply { mkdirs() }
        var sawProgress = false
        val job = launch {
            workspace.clone(REMOTE, target) {
                sawProgress = true
                cancel()
            }
        }
        job.join()

        // Stated separately so a future JGit that stops reporting progress
        // fails as "nothing asked it to cancel" rather than as a leak.
        assertTrue("JGit reported no progress, so nothing cancelled it", sawProgress)
        assertTrue("the clone was not cancelled", job.isCancelled)
        assertFalse("a cancelled clone was left on disk", target.exists())
    }

    /**
     * The cancellation wiring, asserted at the seam it actually runs through.
     *
     * JGit's network calls are blocking, so cancelling the coroutine that
     * launched one does not interrupt it -- the only thing that stops a clone
     * is JGit polling `isCancelled` between work units. A real cancelled clone
     * cannot be made deterministic in a test suite; the poll can.
     */
    @Test
    fun cancelling_the_scope_cancels_the_monitor() {
        val scope = CoroutineScope(Job())
        val reported = mutableListOf<GitProgress>()
        val monitor = CoroutineProgressMonitor(scope, reported::add)

        monitor.beginTask("Receiving objects", 100)
        monitor.update(10)
        assertFalse("cancelled before anything asked it to be", monitor.isCancelled)

        scope.cancel()

        assertTrue("cancelling the scope did not reach JGit", monitor.isCancelled)
        assertEquals(
            listOf<GitProgress>(
                GitProgress.Task("Receiving objects", 100),
                GitProgress.Work("Receiving objects", 10, 100),
            ),
            reported,
        )
    }

    /**
     * JGit signals "I do not know how much work this is" with a constant, not
     * a zero. Reported as null so a caller shows a phase name instead of a bar
     * stuck at nothing.
     */
    @Test
    fun unknown_work_is_reported_as_unknown() {
        val reported = mutableListOf<GitProgress>()
        val monitor = CoroutineProgressMonitor(CoroutineScope(Job()), reported::add)

        monitor.beginTask("Resolving deltas", org.eclipse.jgit.lib.ProgressMonitor.UNKNOWN)
        monitor.update(5)

        assertEquals(
            listOf<GitProgress>(
                GitProgress.Task("Resolving deltas", null),
                GitProgress.Work("Resolving deltas", 5, null),
            ),
            reported,
        )
    }
}
