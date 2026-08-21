package com.osamu.aide.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.ProjectFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class WorkspaceUiState(
    val projectName: String = "",
    /** Flattened visible tree: only expanded directories contribute children. */
    val visibleNodes: List<FileNode> = emptyList(),
    val expandedPaths: Set<String> = emptySet(),
    val selectedFile: File? = null,
)

class WorkspaceViewModel(
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private var rootNode: FileNode? = null

    fun open(projectDir: File) {
        if (rootNode?.file == projectDir) return
        val root = FileNode(projectDir, isDirectory = true, depth = 0)
        rootNode = root
        _state.update {
            WorkspaceUiState(
                projectName = projectDir.name,
                expandedPaths = setOf(projectDir.absolutePath),
            )
        }
        rebuildTree()
    }

    fun toggle(node: FileNode) {
        if (!node.isDirectory) {
            _state.update { it.copy(selectedFile = node.file) }
            return
        }
        val path = node.file.absolutePath
        _state.update { current ->
            val expanded = current.expandedPaths.toMutableSet()
            if (!expanded.remove(path)) expanded.add(path)
            current.copy(expandedPaths = expanded)
        }
        rebuildTree()
    }

    /**
     * Rebuilds the flattened tree off the main thread. Directory listing is
     * cheap per level but a deep project has many levels, and this runs on
     * every expand/collapse.
     */
    private fun rebuildTree() {
        val root = rootNode ?: return
        viewModelScope.launch {
            val expanded = _state.value.expandedPaths
            val flattened = withContext(dispatchers.io) { flatten(root, expanded) }
            _state.update { it.copy(visibleNodes = flattened) }
        }
    }

    private fun flatten(root: FileNode, expanded: Set<String>): List<FileNode> {
        val out = mutableListOf<FileNode>()
        fun walk(node: FileNode) {
            out += node
            if (node.isDirectory && node.file.absolutePath in expanded) {
                ProjectFiles.childrenOf(node).forEach(::walk)
            }
        }
        walk(root)
        return out
    }
}
