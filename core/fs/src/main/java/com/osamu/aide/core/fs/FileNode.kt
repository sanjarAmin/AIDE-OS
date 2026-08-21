package com.osamu.aide.core.fs

import java.io.File

/**
 * One entry in the project file tree.
 *
 * Children are resolved lazily via [ProjectFiles.childrenOf] rather than eagerly
 * recursing: project trees contain `build/` and `.git/` directories with tens of
 * thousands of entries, and walking them on open would stall the UI.
 */
data class FileNode(
    val file: File,
    val isDirectory: Boolean,
    val depth: Int,
) {
    val name: String get() = file.name
}

object ProjectFiles {

    /** Directories that are never worth showing or indexing. */
    private val IGNORED = setOf(".git", "build", ".gradle", ".idea", "node_modules")

    fun childrenOf(node: FileNode): List<FileNode> {
        if (!node.isDirectory) return emptyList()
        val entries = node.file.listFiles() ?: return emptyList()
        return entries
            .filter { it.name !in IGNORED && !it.isHidden }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { FileNode(it, it.isDirectory, node.depth + 1) }
    }

    fun rootOf(project: Project): FileNode =
        FileNode(project.rootDir, isDirectory = true, depth = 0)
}
