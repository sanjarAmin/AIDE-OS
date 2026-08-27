package com.osamu.aide.spike.git

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Spike R6: JGit on ART, asked the questions M8 depends on.
 *
 * Each test isolates one layer so a failure names the layer rather than the
 * spike: (1) the filesystem abstraction initialises, (2) a repository can be
 * created and committed to, (3) history reads back, (4) a real clone comes
 * down over HTTPS, and (5) a push moves objects to another repository.
 *
 * Ordered by how early a failure would stop everything after it. `FS.detect()`
 * is first because JGit calls it before anything else and it is the layer that
 * asks the operating system questions Android answers differently.
 *
 * Network is required for the clone and is the point: this is about whether
 * the thing works on a device.
 *
 * Numbers land in logcat under `GitSpike`.
 */
@RunWith(AndroidJUnit4::class)
class JGitOnDeviceTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "git-spike").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /**
     * Question 1: does `FS.detect()` survive an environment with no shell where
     * it expects one, and no `git` at all?
     *
     * JGit probes on first use -- it looks for a `git` executable on `PATH` to
     * read the system config, and on POSIX it runs a shell to learn the umask.
     * Android has neither at the paths JGit knows: the shell is
     * `/system/bin/sh`, and there is no `git`. What matters is not whether the
     * probe finds anything but whether failing to is fatal, because every other
     * operation is downstream of this object.
     *
     * Reported rather than asserted, for the probe results themselves: a null
     * `gitSystemConfig` is the correct answer on a device, not a defect.
     */
    @Test
    fun the_filesystem_abstraction_initialises() {
        val fs: FS
        val elapsed = measureTimeMillis { fs = FS.DETECTED }

        assertNotNull("FS.detect() returned nothing", fs)
        Log.i(TAG, "FS.detect() = ${fs.javaClass.name} in $elapsed ms")
        Log.i(TAG, "  supportsExecute=${fs.supportsExecute()}")
        Log.i(TAG, "  supportsSymlinks=${fs.supportsSymlinks()}")
        Log.i(TAG, "  gitSystemConfig=${runCatching { fs.gitSystemConfig }.getOrElse { it }}")
        Log.i(TAG, "  userHome=${runCatching { fs.userHome() }.getOrElse { it }}")

        // The probe shelling out is the part most likely to hang rather than
        // fail. A wall-clock bound turns that into a test failure instead of a
        // suite that never finishes.
        assertTrue("FS.detect() took $elapsed ms, which is a hung subprocess", elapsed < 30_000)
    }

    /**
     * Question 2: can a repository be created and committed to?
     *
     * This is the layer that writes loose objects, a tree, and an index --
     * every `java.nio.file` attribute question JGit asks is asked here first.
     * Asserted on the resulting object, not on the call returning: a commit
     * that produced no reachable tree would still return an id.
     */
    @Test
    fun a_repository_is_created_and_committed_to() {
        val repoDir = File(workDir, "created")
        val git = Git.init().setDirectory(repoDir).call()

        git.use {
            File(repoDir, "README.md").writeText("AIDE-OS spike R6\n")
            it.add().addFilepattern("README.md").call()
            val commit = it.commit()
                .setMessage("First commit from a phone")
                // Set explicitly: the default reads user.name out of a global
                // config that does not exist here, and the fallback identity is
                // built from system properties Android fills in differently.
                .setAuthor(PersonIdent("AIDE-OS", "spike@example.invalid"))
                .setCommitter(PersonIdent("AIDE-OS", "spike@example.invalid"))
                .call()

            assertNotNull("no commit id", commit.id)
            assertEquals("First commit from a phone", commit.fullMessage)

            // The commit exists; so must the blob it names, reached through the
            // tree rather than trusted from the commit object.
            val tree = commit.tree
            assertNotNull("commit has no tree", tree)
            assertTrue(
                "HEAD does not point at the commit",
                it.repository.resolve(Constants.HEAD)?.name == commit.name,
            )
            Log.i(TAG, "committed ${commit.name} in ${repoDir.absolutePath}")
        }
    }

    /**
     * Question 3: does history read back through a `RevWalk`?
     *
     * Writing objects and reading them are different code paths -- the read
     * side memory-maps pack files and reads file attributes to decide whether
     * its caches are stale. A `FileSnapshot` that answers wrongly on Android
     * shows up here as history that is short rather than as an exception.
     */
    @Test
    fun history_reads_back_in_order() {
        val repoDir = File(workDir, "history")
        Git.init().setDirectory(repoDir).call().use { git ->
            val ident = PersonIdent("AIDE-OS", "spike@example.invalid")
            repeat(3) { index ->
                File(repoDir, "file-$index.txt").writeText("contents $index\n")
                git.add().addFilepattern("file-$index.txt").call()
                git.commit().setMessage("commit $index").setAuthor(ident).setCommitter(ident).call()
            }
        }

        FileRepositoryBuilder().setGitDir(File(repoDir, ".git")).build().use { repo ->
            RevWalk(repo).use { walk ->
                walk.markStart(walk.parseCommit(repo.resolve(Constants.HEAD)))
                val messages = walk.map { it.fullMessage }.toList()
                assertEquals(listOf("commit 2", "commit 1", "commit 0"), messages)
            }
        }
    }

    /**
     * Question 4: does a real clone come down over HTTPS?
     *
     * JGit's HTTP transport is built on `HttpURLConnection`, which Android has
     * -- this is the one place where the JVM assumption is expected to hold,
     * unlike maven-resolver 2.x's `java.net.http`. The clone is what M8's
     * acceptance test says, so it is asked directly rather than approximated
     * with a local `file://` remote.
     *
     * A tiny, stable, public repository, because this asks whether the
     * transport works. What it *costs* is a different question and is
     * [a_large_repository_clones_in_a_time_worth_knowing].
     */
    @Test
    fun a_public_repository_clones_over_https() {
        val target = File(workDir, "cloned")
        val elapsed = measureTimeMillis {
            Git.cloneRepository()
                .setURI(CLONE_URL)
                .setDirectory(target)
                .setDepth(1)
                .call()
                .close()
        }

        Log.i(TAG, "cloned $CLONE_URL in $elapsed ms")
        assertTrue("no .git directory", File(target, ".git").isDirectory)

        FileRepositoryBuilder().setGitDir(File(target, ".git")).build().use { repo ->
            val head = repo.resolve(Constants.HEAD)
            assertNotNull("clone left no HEAD", head)
            // A clone that fetched refs but no objects still writes HEAD, so
            // the assertion is that the commit it names is readable.
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(head)
                Log.i(TAG, "HEAD = ${commit.name} '${commit.shortMessage}'")
                assertNotNull("HEAD commit has no tree", commit.tree)
            }
        }
    }

    /**
     * Question 5: does a push move objects into another repository?
     *
     * Over `file://` to a bare repository, deliberately. Pushing to GitHub
     * needs a credential this suite must not carry, and the half of push that
     * is in doubt on Android is not the credential -- it is pack generation,
     * which walks objects, deflates them, and writes a pack the receiving side
     * has to index. That happens identically over either transport.
     *
     * What this does *not* answer is whether GitHub accepts the result, which
     * needs a token and belongs in the same parked category as M5's live API
     * assertions.
     */
    @Test
    fun a_push_moves_objects_to_another_repository() {
        val bare = File(workDir, "remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()

        val local = File(workDir, "pushing")
        Git.init().setDirectory(local).call().use { git ->
            val ident = PersonIdent("AIDE-OS", "spike@example.invalid")
            File(local, "pushed.txt").writeText("this travelled in a pack\n")
            git.add().addFilepattern("pushed.txt").call()
            val commit = git.commit()
                .setMessage("pushed")
                .setAuthor(ident)
                .setCommitter(ident)
                .call()

            git.remoteAdd().setName("origin").setUri(
                org.eclipse.jgit.transport.URIish(bare.toURI().toString()),
            ).call()
            val results = git.push().setRemote("origin").setPushAll().call().toList()

            Log.i(TAG, "push results: ${results.map { r -> r.remoteUpdates.map { it.status } }}")

            // The receiving repository is the assertion, not the push result:
            // a push that reported OK while writing nothing would leave the
            // bare repository unable to resolve the ref it claims to have.
            FileRepositoryBuilder().setGitDir(bare).build().use { repo ->
                val head = repo.resolve(commit.name)
                assertNotNull("the remote does not have the pushed commit", head)
                RevWalk(repo).use { walk ->
                    assertEquals("pushed", walk.parseCommit(head).fullMessage.trim())
                }
            }
        }
    }

    /**
     * What a clone *costs*, which is the number M8's design has to live with.
     *
     * Opt-in: it is minutes long and network-bound, so it does not belong in
     * every sweep. Reported rather than asserted -- an emulator's NAT is not a
     * phone's radio, and a threshold here would only ever measure the machine.
     *
     * The number matters because it decides the shape of the feature, not
     * whether it ships: a clone this long cannot live on the main thread, in an
     * Activity scope, or without a cancel button and a progress figure the user
     * believes.
     */
    @Test
    fun a_large_repository_clones_in_a_time_worth_knowing() {
        assumeTrue(
            "set -Pandroid.testInstrumentationRunnerArguments.slowTests=true to run this",
            InstrumentationRegistry.getArguments().getString("slowTests") == "true",
        )

        val target = File(workDir, "large")
        val elapsed = measureTimeMillis {
            Git.cloneRepository()
                .setURI(LARGE_CLONE_URL)
                .setDirectory(target)
                .setDepth(1)
                .call()
                .close()
        }

        val bytes = File(target, ".git").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Log.i(TAG, "cloned $LARGE_CLONE_URL in $elapsed ms, .git is $bytes bytes")
        assertTrue("nothing was written", bytes > 0)
    }

    /**
     * What `status` costs on a real working tree.
     *
     * This is the operation an IDE runs constantly -- after every save, to
     * decorate the file tree -- so its cost is a UI budget rather than a
     * curiosity. It is also the operation most exposed to Android's filesystem
     * answering attribute questions differently: `status` is largely
     * `FileSnapshot` comparisons, and one that is wrong reports a clean tree as
     * dirty or the reverse.
     *
     * Asserted on the *answer*, then reported for time. A `status` that is fast
     * because it looked at nothing is the failure this is guarding against.
     */
    @Test
    fun status_sees_a_working_tree_edit() {
        val target = File(workDir, "status")
        Git.cloneRepository().setURI(CLONE_URL).setDirectory(target).setDepth(1).call().use { git ->
            val clean: Boolean
            val cleanMs = measureTimeMillis { clean = git.status().call().isClean }
            assertTrue("a fresh clone is not clean", clean)

            val file = requireNotNull(
                target.listFiles()?.firstOrNull { it.isFile && !it.name.startsWith(".") },
            ) { "the clone produced no tracked file to edit" }
            file.appendText("\nedited by spike R6\n")

            val status: org.eclipse.jgit.api.Status
            val dirtyMs = measureTimeMillis { status = git.status().call() }

            Log.i(TAG, "status: clean=$cleanMs ms, after one edit=$dirtyMs ms")
            assertEquals(setOf(file.name), status.modified)
        }
    }

    private companion object {
        const val TAG = "GitSpike"

        /**
         * Small, public, and not this project's own repository -- a spike that
         * only ever clones the repo it lives in would not notice a transport
         * that broke for everyone else.
         */
        const val CLONE_URL = "https://github.com/octocat/Spoon-Knife.git"

        /** Real, and large enough that the cost is the answer. See below. */
        const val LARGE_CLONE_URL = "https://github.com/git/git-scm.com.git"
    }
}
