package com.osamu.aide.vcs.git

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.Closeable
import java.io.File
import java.util.Date
import java.util.TimeZone

/**
 * One repository on disk, wrapped so callers get [AppResult] rather than
 * exceptions and never touch a JGit type.
 *
 * **Not thread-safe, and holds an open handle.** JGit's `Git` owns a
 * `Repository` with file locks and memory-mapped packs; it must be closed, and
 * two of them open on one directory will fight over the index lock. So this is
 * [Closeable] and a caller keeps exactly one per repository.
 *
 * Every method that touches disk runs on [DispatcherProvider.io]. That is not
 * ceremony: spike R6 measured `status` at 11-13 ms on a small tree, which is
 * fine, and a clone at 254 s, which is not -- and the two go through the same
 * blocking JGit API with nothing in the types to tell them apart.
 *
 * Identity comes from [GitIdentityStore] rather than from JGit's default,
 * because on a device there is no `~/.gitconfig` for JGit to read and its
 * fallback is assembled from Android's system properties. See
 * `tools/git/FINDINGS.md` finding 1.
 */
class GitRepository internal constructor(
    private val git: Git,
    private val identities: GitIdentityStore,
    private val credentials: GitCredentialStore,
    private val dispatchers: DispatcherProvider,
) : Closeable {

    /** The work tree, for a caller that needs to turn a status path into a file. */
    val workTree: File get() = git.repository.workTree

    /**
     * The checked-out branch, or null when HEAD is detached.
     *
     * Null is a real state -- a fresh shallow clone of a tag is detached -- and
     * is not an error, so it is not an [AppResult].
     */
    suspend fun currentBranch(): String? = withContext(dispatchers.io) {
        runCatching { git.repository.branch?.takeIf { git.repository.fullBranch != it } }.getOrNull()
    }

    /** What the working tree looks like. Cheap enough to call after a save. */
    suspend fun status(): AppResult<GitStatus> = io("Could not read the repository status") {
        val status = git.status().call()
        AppResult.Success(
            GitStatus(
                added = status.added.toSet(),
                changed = status.changed.toSet(),
                removed = status.removed.toSet(),
                modified = status.modified.toSet(),
                missing = status.missing.toSet(),
                untracked = status.untracked.toSet(),
                conflicting = status.conflicting.toSet(),
            ),
        )
    }

    /**
     * Stages [paths], which are repository-relative.
     *
     * One call, deletions included. This was written as two -- `add` then
     * `add --update` -- on the assumption that JGit's `add` records content
     * only, the way `git add <path>` did before git 2.0. **Measured: it does
     * not.** Staging a path whose file is gone moves it straight to `removed`,
     * so the second call was dead code, and
     * [GitRepositoryTest.staging_records_a_deletion] now pins the real
     * behaviour rather than the assumed one.
     */
    suspend fun stage(paths: Collection<String>): AppResult<Unit> =
        io("Could not stage those files") {
            if (paths.isEmpty()) return@io AppResult.Success(Unit)
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()
            AppResult.Success(Unit)
        }

    /** Unstages [paths], leaving the working tree alone. */
    suspend fun unstage(paths: Collection<String>): AppResult<Unit> =
        io("Could not unstage those files") {
            if (paths.isEmpty()) return@io AppResult.Success(Unit)
            val reset = git.reset()
            paths.forEach { reset.addPath(it) }
            reset.call()
            AppResult.Success(Unit)
        }

    /**
     * Commits what is staged.
     *
     * Fails rather than defaults when no identity is stored: a commit with an
     * invented author is not a smaller problem than no commit, because fixing
     * authorship afterwards means rewriting history.
     *
     * An empty commit is refused. JGit will happily make one, and on a phone
     * the likeliest way to reach this is tapping Commit twice.
     */
    suspend fun commit(message: String): AppResult<GitCommit> =
        io("Could not commit") {
            val identity = identities.read()
                ?: return@io AppResult.Failure(
                    AppError(
                        "No commit identity is set. Add a name and email before committing -- " +
                            "git writes them into every commit and they cannot be changed " +
                            "afterwards without rewriting history.",
                    ),
                )
            if (message.isBlank()) {
                return@io AppResult.Failure(AppError("A commit needs a message."))
            }

            val conflicts = git.status().call().conflicting
            if (conflicts.isNotEmpty()) {
                return@io AppResult.Failure(
                    AppError("Resolve the conflicts first: ${conflicts.sorted().joinToString()}"),
                )
            }

            val person = PersonIdent(identity.name, identity.email)
            val commit = try {
                git.commit()
                    .setMessage(message)
                    // Both, explicitly. Leaving either to JGit reintroduces the
                    // fabricated identity this exists to avoid.
                    .setAuthor(person)
                    .setCommitter(person)
                    .setAllowEmpty(false)
                    .call()
            } catch (empty: EmptyCommitException) {
                return@io AppResult.Failure(
                    AppError("Nothing is staged, so there is nothing to commit.", empty),
                )
            }
            AppResult.Success(commit.toModel())
        }

    /**
     * The most recent [limit] commits reachable from HEAD, newest first.
     *
     * Empty rather than a failure on a repository with no commits: a freshly
     * initialised repository has no HEAD to walk, and that is the normal state
     * of one, not an error to report.
     */
    suspend fun log(limit: Int = 50): AppResult<List<GitCommit>> =
        io("Could not read the history") {
            if (git.repository.resolve(Constants.HEAD) == null) {
                return@io AppResult.Success(emptyList())
            }
            AppResult.Success(git.log().setMaxCount(limit).call().map { it.toModel() })
        }

    /**
     * Pushes the current branch to [remote].
     *
     * The credential comes from [GitCredentialStore], keyed on the remote's
     * host. A missing one fails with a message naming the host rather than
     * letting JGit report an authentication error the user cannot act on.
     *
     * **The result is checked, not assumed.** JGit reports a rejected update
     * -- non-fast-forward, or a hook refusing it -- through the returned
     * statuses rather than by throwing, so a push that changed nothing on the
     * far side returns normally.
     */
    suspend fun push(
        remote: String = Constants.DEFAULT_REMOTE_NAME,
        onProgress: (GitProgress) -> Unit = {},
    ): AppResult<Unit> = io("Could not push") {
        val branch = git.repository.branch
            ?: return@io AppResult.Failure(
                AppError("HEAD is detached, so there is nothing to push."),
            )

        val url = remoteUrl(remote)
            ?: return@io AppResult.Failure(
                AppError("There is no remote called '$remote' to push to."),
            )
        val provider = credentialsFor(url)
        // Only http(s) needs one. A `file://` remote -- which is what a local
        // backup or a test pushes to -- has no host and no credential, and
        // demanding a token for it would refuse a push that would have worked.
        if (provider == null && needsToken(url)) {
            return@io AppResult.Failure(missingToken(url))
        }

        val monitor = CoroutineProgressMonitor(CoroutineScope(currentCoroutineContext()), onProgress)
        val results = git.push()
            .setRemote(remote)
            .setRefSpecs(RefSpec("$REFS_HEADS$branch:$REFS_HEADS$branch"))
            .apply { provider?.let { setCredentialsProvider(it) } }
            .setProgressMonitor(monitor)
            .call()

        val rejected = results
            .flatMap { it.remoteUpdates }
            .filterNot { it.status in ACCEPTED }
        if (rejected.isNotEmpty()) {
            return@io AppResult.Failure(
                AppError(
                    GitCredentialStore.redact(
                        rejected.joinToString("; ") { update ->
                            "${update.remoteName}: ${update.status}" +
                                (update.message?.let { " -- $it" } ?: "")
                        },
                    ),
                ),
            )
        }
        onProgress(GitProgress.Done)
        AppResult.Success(Unit)
    }

    /**
     * Fetches from [remote] without touching the working tree.
     *
     * Separate from any merge on purpose. Fetching is safe and can run in the
     * background; merging can conflict, and a background operation that leaves
     * a working tree in conflict is a trap. What to do with what arrived is the
     * caller's decision.
     *
     * A token is used when one is stored and omitted when it is not, because
     * a public repository fetches anonymously -- unlike push, where an
     * anonymous attempt can only fail.
     */
    suspend fun fetch(
        remote: String = Constants.DEFAULT_REMOTE_NAME,
        onProgress: (GitProgress) -> Unit = {},
    ): AppResult<Unit> = io("Could not fetch") {
        val monitor = CoroutineProgressMonitor(CoroutineScope(currentCoroutineContext()), onProgress)
        git.fetch()
            .setRemote(remote)
            .apply { remoteUrl(remote)?.let(::credentialsFor)?.let { setCredentialsProvider(it) } }
            .setProgressMonitor(monitor)
            .call()
        onProgress(GitProgress.Done)
        AppResult.Success(Unit)
    }

    /** The configured URL of [remote], or null when there is no such remote. */
    fun remoteUrl(remote: String = Constants.DEFAULT_REMOTE_NAME): String? =
        git.repository.config
            .getString(REMOTE_SECTION, remote, URL_KEY)
            ?.takeIf { it.isNotBlank() }

    override fun close() = git.close()

    private fun credentialsFor(url: String): CredentialsProvider? {
        val host = GitCredentialStore.hostOf(url) ?: return null
        val token = credentials.read(host) ?: return null
        // A personal access token goes in the *username* field for some hosts
        // and the password field for others; every major provider accepts it as
        // the password with any non-empty username, so that is what is sent.
        return UsernamePasswordCredentialsProvider(TOKEN_USERNAME, token)
    }

    /** Only a web remote can ask for one, and only that one is worth refusing early for. */
    private fun needsToken(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private fun missingToken(url: String): AppError = AppError(
        "No access token is stored for ${GitCredentialStore.hostOf(url) ?: url}. " +
            "Add one before pushing.",
    )

    /**
     * Runs [block] on the IO dispatcher, turning anything thrown into a failure
     * that names the operation.
     *
     * JGit's exception messages are written for a developer reading a stack
     * trace -- `RefNotAdvertisedException`, `TransportException: ... 401` -- so
     * the caller's message leads and JGit's follows it. The cause is kept for
     * logging, never shown alone.
     */
    private suspend inline fun <T> io(
        failure: String,
        crossinline block: suspend () -> AppResult<T>,
    ): AppResult<T> = withContext(dispatchers.io) {
        try {
            block()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Never swallowed into a Failure: a cancelled coroutine must stay
            // cancelled, and reporting it as an error would put a message on
            // screen for something the user asked for.
            throw cancellation
        } catch (failed: Exception) {
            AppResult.Failure(
                AppError(
                    "$failure: ${GitCredentialStore.redact(failed.message.orEmpty())}".trimEnd(':', ' '),
                    failed,
                ),
            )
        }
    }

    private companion object {
        const val REFS_HEADS = "refs/heads/"
        const val REMOTE_SECTION = "remote"
        const val URL_KEY = "url"

        /**
         * Ignored by every provider that accepts token-as-password, but it may
         * not be empty. Not the user's account name: that would be one more
         * thing to store and to get wrong.
         */
        const val TOKEN_USERNAME = "aide-os"

        /** Everything else JGit can report is a refusal. */
        val ACCEPTED = setOf(
            RemoteRefUpdate.Status.OK,
            RemoteRefUpdate.Status.UP_TO_DATE,
        )
    }
}

/** Flattens a JGit commit, converting its second-precision time to millis. */
internal fun RevCommit.toModel(): GitCommit = GitCommit(
    id = name,
    abbreviated = abbreviate(ABBREVIATION_LENGTH).name(),
    summary = shortMessage,
    fullMessage = fullMessage,
    authorName = authorIdent?.name.orEmpty(),
    authorEmail = authorIdent?.emailAddress.orEmpty(),
    // JGit stores commit time as seconds; `commitTime` is an Int and overflows
    // in 2038 if multiplied as one.
    timestamp = commitTime.toLong() * 1_000L,
)

private const val ABBREVIATION_LENGTH = 7
