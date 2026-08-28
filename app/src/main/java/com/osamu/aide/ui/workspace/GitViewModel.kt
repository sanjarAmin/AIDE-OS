package com.osamu.aide.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.vcs.git.GitCommit
import com.osamu.aide.vcs.git.GitIdentityStore
import com.osamu.aide.vcs.git.GitProgress
import com.osamu.aide.vcs.git.GitRepository
import com.osamu.aide.vcs.git.GitStatus
import com.osamu.aide.vcs.git.GitWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GitUiState(
    /** Null until [open] has looked; false means the project is not in a repository. */
    val isRepository: Boolean? = null,
    val branch: String? = null,
    val status: GitStatus = GitStatus(),
    val recent: List<GitCommit> = emptyList(),
    val message: String = "",
    /** True while an operation is running; the panel's buttons go with it. */
    val isBusy: Boolean = false,
    /** What a push is doing, or null. Reported for the same reason a clone's is. */
    val progress: String? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
    /** False when no identity is stored, which blocks committing rather than faking one. */
    val hasIdentity: Boolean = false,
    /** The diff being looked at, or null. */
    val diff: GitDiff? = null,
)

/**
 * One file's changes, ready to show.
 *
 * [staged] is carried so the view can say *which* diff this is. The working
 * tree against the index and the index against `HEAD` are different diffs of
 * the same file, and a screen that showed one while labelling it the other
 * would be wrong half the time.
 */
data class GitDiff(
    val path: String,
    val staged: Boolean,
    val text: String,
)

/**
 * The workspace's git panel.
 *
 * Separate from [WorkspaceViewModel] rather than folded into it, the same way
 * [AssistantViewModel] is: this owns a [GitRepository], which holds file locks
 * and memory-mapped packs and has to be closed. Putting that lifetime inside a
 * view model that already carries the editor, the build and the language
 * service would make it the one thing nobody remembers to release.
 *
 * **The repository is not the project.** A cloned Android repository is a
 * Gradle root holding `app/`, and `app/` is what the editor opens -- so this
 * walks up from the project to find the repository, bounded by the workspace so
 * it cannot adopt something the user never mentioned.
 */
class GitViewModel(
    private val git: GitWorkspace,
    private val identities: GitIdentityStore,
    private val workspaceRoot: File,
) : ViewModel() {

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    private var repository: GitRepository? = null

    /**
     * Set synchronously, before the coroutine that opens the repository starts.
     *
     * Guarding on [repository] alone is not enough: `open` is called from a
     * `LaunchedEffect`, and two recompositions close enough together both see a
     * null repository and both open one. The second wins the field and the
     * first is leaked with the index locked against everything after it.
     */
    private var opening = false

    /** Remembered so [initialise] knows where to create the repository. */
    private var projectDir: File? = null

    /**
     * Points the panel at the repository containing [projectDir].
     *
     * Runs once per view model, which is once per opened project: the workspace
     * calls this from a `LaunchedEffect` that re-runs on every configuration
     * change, and opening a second [GitRepository] on one directory means two
     * of them fighting over the index lock.
     *
     * The guard is not reset on failure, deliberately. Both failures here are
     * terminal for this screen -- the project is not in a repository, or the
     * repository will not open -- and retrying on the next recomposition would
     * do the same work to reach the same answer.
     */
    fun open(projectDir: File) {
        this.projectDir = projectDir
        if (opening) return
        opening = true
        viewModelScope.launch {
            val root = git.enclosingRepository(projectDir, ceiling = workspaceRoot)
            if (root == null) {
                _state.update { it.copy(isRepository = false) }
                return@launch
            }
            when (val opened = git.open(root)) {
                is AppResult.Success -> {
                    repository = opened.value
                    // **Loaded before it is announced.** Publishing
                    // isRepository = true first left a window where the panel
                    // had a repository but no branch, status or history yet --
                    // so it rendered "detached HEAD" and an empty file list for
                    // an instant on every open. Harmless-looking, wrong, and it
                    // only failed a test under full-suite timing.
                    reload()
                    _state.update { it.copy(isRepository = true) }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isRepository = false, errorMessage = opened.error.message)
                }
            }
        }
    }

    /**
     * Creates a repository for a project that has none.
     *
     * A project that was created rather than cloned is not under version
     * control, and until this existed there was no way to change that from the
     * app: the panel said so and offered nothing. `git init` is the whole of
     * the answer and it was already implemented one layer down.
     *
     * The repository is created at the project directory, not at its parent.
     * A created project has no enclosing repository to belong to, so there is
     * no parent to prefer -- and initialising one above the project would put
     * every other project in the workspace inside it.
     */
    fun initialise() {
        val directory = projectDir ?: return
        if (_state.value.isBusy || _state.value.isRepository == true) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, errorMessage = null, notice = null) }
            when (val created = git.init(directory)) {
                is AppResult.Success -> {
                    repository = created.value
                    _state.update {
                        it.copy(isRepository = true, isBusy = false, notice = "Repository created.")
                    }
                    reload()
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isBusy = false, errorMessage = created.error.message)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val repo = repository ?: return
        // Read before the state is touched, so a failure leaves the panel
        // showing the last thing that was true rather than an empty one.
        val status = repo.status()
        val log = repo.log(limit = RECENT_COMMITS)
        val branch = repo.currentBranch()
        val hasIdentity = identities.read() != null
        _state.update {
            it.copy(
                branch = branch,
                status = (status as? AppResult.Success)?.value ?: it.status,
                recent = (log as? AppResult.Success)?.value ?: it.recent,
                hasIdentity = hasIdentity,
                // **Only replaced when reading the status actually failed.**
                // Every operation here reloads afterwards, so clearing this
                // unconditionally erases the message the operation just set --
                // a commit refused for having no identity failed in complete
                // silence, which is how this was found.
                errorMessage = (status as? AppResult.Failure)?.error?.message ?: it.errorMessage,
            )
        }
    }

    fun setMessage(message: String) = _state.update { it.copy(message = message) }

    /**
     * Loads one file's diff for display.
     *
     * Loaded on demand rather than with the status: a repository can have
     * hundreds of changed files, and reading every diff to show a list of names
     * would make opening the panel cost what looking at one file costs.
     */
    fun showDiff(path: String, staged: Boolean) {
        val repo = repository ?: return
        viewModelScope.launch {
            when (val result = repo.diff(path, staged)) {
                is AppResult.Success -> _state.update {
                    it.copy(diff = GitDiff(path, staged, result.value))
                }
                is AppResult.Failure -> _state.update {
                    it.copy(errorMessage = result.error.message)
                }
            }
        }
    }

    fun dismissDiff() = _state.update { it.copy(diff = null) }

    fun stage(paths: Collection<String>) = mutate { it.stage(paths) }

    fun unstage(paths: Collection<String>) = mutate { it.unstage(paths) }

    /**
     * Commits what is staged, then clears the message.
     *
     * The message survives a failure on purpose. A rejected commit with the
     * text gone means retyping it, and the commonest rejection -- nothing
     * staged -- is one the user fixes in two taps and retries.
     */
    fun commit() {
        val message = _state.value.message
        mutate(
            onSuccess = { _state.update { it.copy(message = "") } },
            block = { it.commit(message) },
        )
    }

    /**
     * Pushes the current branch.
     *
     * Reports progress for the same reason a clone does: this is the only other
     * operation here that can take minutes, and JGit's monitor is the only
     * thing that knows how far along it is.
     */
    fun push() {
        val repo = repository ?: return
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, errorMessage = null, notice = null) }
            val result = repo.push { progress ->
                _state.update { it.copy(progress = progress.describe()) }
            }
            _state.update {
                when (result) {
                    is AppResult.Success ->
                        it.copy(isBusy = false, progress = null, notice = "Pushed.")
                    is AppResult.Failure ->
                        it.copy(isBusy = false, progress = null, errorMessage = result.error.message)
                }
            }
            reload()
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Runs one repository operation, then reloads.
     *
     * The reload is not optional. Every operation here changes what `status`
     * would say, and a panel showing the state from before the tap is one the
     * user taps again.
     */
    private fun mutate(
        onSuccess: () -> Unit = {},
        block: suspend (GitRepository) -> AppResult<*>,
    ) {
        val repo = repository ?: return
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, errorMessage = null, notice = null) }
            when (val result = block(repo)) {
                is AppResult.Success -> onSuccess()
                is AppResult.Failure ->
                    _state.update { it.copy(errorMessage = result.error.message) }
            }
            reload()
            _state.update { it.copy(isBusy = false) }
        }
    }

    private fun GitProgress.describe(): String = when (this) {
        is GitProgress.Task -> title.ifBlank { "Working…" }
        is GitProgress.Work -> if (total == null) title else "$title  $completed/$total"
        GitProgress.Done -> "Finishing…"
    }

    override fun onCleared() {
        // The repository holds file locks and mapped packs. Leaking one keeps
        // the index locked against the next thing that opens the project.
        repository?.close()
        repository = null
    }

    private companion object {
        /** Enough to see what just happened, not a history browser. */
        const val RECENT_COMMITS = 10
    }
}
