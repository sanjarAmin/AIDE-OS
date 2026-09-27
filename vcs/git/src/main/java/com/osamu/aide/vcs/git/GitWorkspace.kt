package com.osamu.aide.vcs.git

import android.content.Context
import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Opens, initialises and clones repositories.
 *
 * The entry point to this module: a caller gets a [GitRepository] from here and
 * nothing else constructs one. That is what keeps the identity and credential
 * stores in one place rather than passed around by every call site.
 *
 * **Repositories live on internal storage.** `:core:fs` already copies imported
 * projects there because aapt2 needs a real filesystem path and a SAF document
 * has none; a git work tree needs one for the same reason plus a second, which
 * is that JGit's index and locking assume a filesystem it can set attributes
 * on. `tools/git/FINDINGS.md` finding 1.
 */
class GitWorkspace(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val identities: GitIdentityStore = GitIdentityStore(context),
    private val credentials: GitCredentialStore = GitCredentialStore(context),
) {

    /** Whether [directory] is the root of a repository. Does not open it. */
    fun isRepository(directory: File): Boolean = File(directory, Constants.DOT_GIT).exists()

    /**
     * Opens the repository rooted at [directory].
     *
     * Refuses a directory that is merely *inside* a repository. JGit's builder
     * will happily walk up and find an enclosing `.git`, which on a device
     * means opening the wrong project because one workspace folder happened to
     * sit under another. The caller knows which root it means; this does not
     * guess.
     */
    suspend fun open(directory: File): AppResult<GitRepository> =
        withContext(dispatchers.io) {
            val dotGit = File(directory, Constants.DOT_GIT)
            if (!dotGit.exists()) {
                return@withContext AppResult.Failure(
                    AppError("${directory.name} is not a git repository."),
                )
            }
            runCatching {
                val repository = FileRepositoryBuilder()
                    .setGitDir(if (dotGit.isDirectory) dotGit else directory)
                    .readEnvironment()
                    .build()
                GitRepository(Git(repository), identities, credentials, dispatchers)
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    AppResult.Failure(AppError("Could not open ${directory.name}: ${it.message}", it))
                },
            )
        }

    /**
     * The repository [directory] belongs to, or null.
     *
     * Walks up, which [open] deliberately refuses to do -- and the difference
     * is that this is asked for. A project's root is usually *not* the
     * repository root: a cloned Android repository is a Gradle root holding
     * `app/`, and `app/` is what the editor and the build engine open. Version
     * control still means the whole clone.
     *
     * Bounded by [ceiling] so the walk cannot leave the workspace and adopt
     * some enclosing repository the user never asked about -- on a device with
     * the workspace on shared storage, that would otherwise be reachable.
     */
    fun enclosingRepository(directory: File, ceiling: File): File? {
        val stop = runCatching { ceiling.canonicalFile }.getOrNull() ?: return null
        var current = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        while (true) {
            if (isRepository(current)) return current
            if (current == stop) return null
            current = current.parentFile ?: return null
        }
    }

    /**
     * Initialises a repository at [directory], creating it if needed.
     *
     * Refuses an existing repository rather than re-initialising one. `git
     * init` on an existing repository is harmless on a desktop and alarming on
     * a phone, where the button that reaches it is next to the one that opens
     * a project.
     */
    suspend fun init(directory: File): AppResult<GitRepository> =
        withContext(dispatchers.io) {
            if (isRepository(directory)) {
                return@withContext AppResult.Failure(
                    AppError("${directory.name} is already a git repository."),
                )
            }
            runCatching {
                val git = Git.init().setDirectory(directory.apply { mkdirs() }).call()
                GitRepository(git, identities, credentials, dispatchers)
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    AppResult.Failure(AppError("Could not create a repository: ${it.message}", it))
                },
            )
        }

    /**
     * Clones [url] into [directory].
     *
     * **This is the long one.** Spike R6 measured 254 s and 179 MB for a
     * depth-1 clone of a large repository, so three things are not optional:
     * it runs off the main thread, it reports progress a user can believe, and
     * cancelling the calling coroutine actually stops it --
     * [CoroutineProgressMonitor] polls the scope so JGit gives up between work
     * units rather than running to completion behind a dismissed dialog.
     *
     * **Nothing is left behind on failure or cancellation, empty directory
     * included.** Measured rather than assumed: JGit cleans up after itself,
     * on a bad host, on a remote that is not a repository, and on a clone it
     * abandoned because the monitor said to -- but only *if it created the
     * directory*. Hand it one that already exists and it removes the contents
     * and leaves the directory standing.
     *
     * That case is reachable here, because an existing empty directory is
     * accepted above: a file picker that created the folder before the user
     * chose it produces exactly one. So the deletion covers the leftover, and
     * the tests pre-create the directory, because without that they assert
     * only that JGit does its own job.
     *
     * [depth] defaults to null, meaning full history. **Shallow bounds history,
     * not content** -- the 179 MB above was at depth 1 -- so it is not the
     * answer to a storage question and the UI should not present it as one.
     */
    suspend fun clone(
        url: String,
        directory: File,
        depth: Int? = null,
        onProgress: (GitProgress) -> Unit = {},
    ): AppResult<GitRepository> = withContext(dispatchers.io) {
        if (directory.exists() && directory.list()?.isNotEmpty() == true) {
            return@withContext AppResult.Failure(
                AppError("${directory.name} already exists and is not empty."),
            )
        }
        // A host is what a token is keyed on, **not** what makes a URL valid: a
        // `file://` remote has none and is perfectly cloneable. Requiring one
        // here refused every local clone, which is how the tests found it.
        val host = GitCredentialStore.hostOf(url)
        if (runCatching { java.net.URI(url.trim()).scheme }.getOrNull().isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppError("That does not look like a repository URL."),
            )
        }

        val scope = CoroutineScope(currentCoroutineContext())

        try {
            val git = Git.cloneRepository()
                .setURI(url.trim())
                .setDirectory(directory)
                // Anonymous when no token is stored: a public clone needs none,
                // and sending one to a host the user did not mean to would be
                // worse than a prompt.
                .apply {
                    host?.let(credentials::read)?.let {
                        setCredentialsProvider(UsernamePasswordCredentialsProvider(TOKEN_USERNAME, it))
                    }
                    depth?.let { setDepth(it) }
                }
                .setProgressMonitor(CoroutineProgressMonitor(scope, onProgress))
                .call()

            // JGit's monitor is polled between work units, so a cancel arriving
            // during the last one lands here instead. Checked before the result
            // is handed back so a cancelled clone never looks like a successful
            // one.
            currentCoroutineContext().ensureActive()

            onProgress(GitProgress.Done)
            AppResult.Success(GitRepository(git, identities, credentials, dispatchers))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            directory.deleteRecursively()
            throw cancellation
        } catch (failed: Exception) {
            directory.deleteRecursively()
            AppResult.Failure(
                AppError(
                    "Could not clone: ${GitCredentialStore.redact(failed.message.orEmpty())}"
                        .trimEnd(':', ' '),
                    failed,
                ),
            )
        }
    }

    private companion object {
        const val TOKEN_USERNAME = "aide-os"
    }
}
