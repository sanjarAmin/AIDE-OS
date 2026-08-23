package com.osamu.aide.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectImporter
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.core.common.AppResult
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val isLoading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val errorMessage: String? = null,
    /** True while a picked folder is being copied in, which is not instant. */
    val isImporting: Boolean = false,
)

class ProjectsViewModel(
    private val repository: ProjectRepository,
    private val importer: ProjectImporter,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
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

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
