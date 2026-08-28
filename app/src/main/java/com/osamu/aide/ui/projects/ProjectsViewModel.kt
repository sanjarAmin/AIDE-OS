package com.osamu.aide.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectAdoption
import com.osamu.aide.core.fs.ProjectImporter
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.core.common.AppResult
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.osamu.aide.core.fs.ProjectDescriptor
import com.osamu.aide.vcs.git.GitProgress
import com.osamu.aide.vcs.git.GitWorkspace
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class ProjectsUiState(
    val isLoading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val errorMessage: String? = null,
    /** True while a picked folder is being copied in, which is not instant. */
    val isImporting: Boolean = false,
    /**
     * What the clone is doing, or null when none is running.
     *
     * A phase name rather than a fraction, because JGit reports a total it does
     * not always know -- and a bar stuck at zero for four minutes is worse than
     * a line of text that changes. Spike R6 measured 254 s for a large
     * repository, so this is the difference between waiting and force-quitting.
     */
    val cloneStatus: String? = null,
)

class ProjectsViewModel(
    private val repository: ProjectRepository,
    private val importer: ProjectImporter,
    private val git: GitWorkspace,
    private val adoption: ProjectAdoption,
    private val workspaceRoot: File,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    /** Held so [cancelClone] has something to cancel. */
    private var cloneJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /**
     * The reload itself, so a caller already inside a coroutine can await it.
     *
     * That matters because a successful reload **clears the error**, which is
     * right when it is the last thing to happen and wrong when it races
     * something that just set one. Cloning hit exactly that: an adoption
     * failure was reported and then wiped by the refresh launched after it, so
     * a repository with no Android module failed completely silently.
     */
    private suspend fun reload() {
        _state.update { it.copy(isLoading = true) }
        when (val result = repository.listProjects()) {
            is AppResult.Success -> _state.update {
                it.copy(isLoading = false, projects = result.value, errorMessage = null)
            }
            is AppResult.Failure -> _state.update {
                it.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun createProject(name: String, language: SourceLanguage) {
        viewModelScope.launch {
            val applicationId = "com.example." + name.lowercase().filter { it.isLetterOrDigit() }
                .ifEmpty { "app" }
            val result = repository.createProject(
                name = name,
                applicationId = applicationId,
                language = language,
                engine = BuildEngine.FAST,
            )
            when (result) {
                is AppResult.Success -> refresh()
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    /**
     * Copies the folder behind [tree] into the workspace.
     *
     * The copy is the point rather than an implementation detail -- see
     * [ProjectImporter]. It is why this reports progress at all: a Gradle
     * project is thousands of files, and a screen that sat still through it
     * would read as a failed pick.
     */
    fun importProject(tree: Uri) {
        if (_state.value.isImporting) return
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, errorMessage = null) }
            when (val result = importer.import(tree)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isImporting = false) }
                    refresh()
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isImporting = false, errorMessage = result.error.message)
                }
            }
        }
    }

    /**
     * Clones [url] into the workspace and adopts the result as a project.
     *
     * Two steps rather than one, and the second can fail on its own: a
     * repository that clones perfectly well may hold no Android module, or two
     * of them. That is reported as an error rather than swallowed, and **the
     * clone is kept** -- the user paid for those bytes, and deleting them
     * because we could not name a module would be the worse outcome of the two.
     *
     * The name comes from the URL's last segment, which is what every git
     * client does and what the user already has in their head.
     */
    fun cloneRepository(url: String) {
        if (cloneJob?.isActive == true) return

        val directory = File(workspaceRoot, ProjectDescriptor.directoryNameFor(nameFor(url)))
        cloneJob = viewModelScope.launch {
            _state.update { it.copy(cloneStatus = "Connecting…", errorMessage = null) }

            val cloned = git.clone(url, directory) { progress ->
                _state.update { it.copy(cloneStatus = progress.describe()) }
            }
            when (cloned) {
                is AppResult.Failure -> {
                    _state.update { it.copy(cloneStatus = null, errorMessage = cloned.error.message) }
                    return@launch
                }
                // Closed immediately: the clone is on disk and nothing here
                // holds it open. A GitRepository owns file locks and mapped
                // packs, and leaking one per clone would fight the next
                // operation on the same directory.
                is AppResult.Success -> cloned.value.close()
            }

            val adopted = adoption.adopt(directory, nameFor(url))
            // Reloaded *before* the outcome is reported, because a successful
            // reload clears errorMessage -- doing it after would erase the
            // message this just set.
            reload()
            _state.update {
                it.copy(
                    cloneStatus = null,
                    errorMessage = when (adopted) {
                        is AppResult.Success -> null
                        is AppResult.Failure -> "Cloned, but " +
                            adopted.error.message.replaceFirstChar(Char::lowercaseChar)
                    },
                )
            }
        }
    }

    /**
     * Stops a running clone.
     *
     * Real rather than cosmetic: cancelling the coroutine reaches JGit through
     * the progress monitor, which polls it between work units, and
     * `GitWorkspace.clone` deletes what it wrote. Without that the user watches
     * a dismissed dialog finish writing 179 MB they asked not to have.
     */
    fun cancelClone() {
        cloneJob?.cancel()
        cloneJob = null
        _state.update { it.copy(cloneStatus = null) }
    }

    private fun nameFor(url: String): String = url.trim()
        .removeSuffix("/")
        .substringAfterLast('/')
        .removeSuffix(".git")
        .ifBlank { "repository" }

    private fun GitProgress.describe(): String = when (this) {
        is GitProgress.Task -> title.ifBlank { "Working…" }
        is GitProgress.Work ->
            if (total == null) title else "$title  $completed/$total"
        GitProgress.Done -> "Finishing…"
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
