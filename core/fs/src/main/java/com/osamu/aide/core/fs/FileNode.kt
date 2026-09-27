package com.osamu.aide.core.fs

import java.io.File
import java.nio.file.Files

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
    /**
     * What the row says, which is usually the file's own name.
     *
     * A run of directories that each hold nothing but the next one is shown as
     * a single row -- `java/com/example/tabscheck` -- so [file] is the *end* of
     * that run and this is the whole of it. See [ProjectFiles.childrenOf].
     */
    val name: String = file.name,
)

object ProjectFiles {

    /** Directories that are never worth showing or indexing. */
    private val IGNORED = setOf(".git", "build", ".gradle", ".idea", "node_modules")

    /**
     * The longest chain of single-child directories that will be folded into
     * one row.
     *
     * A bound rather than a belief that deep chains do not exist: a symlink
     * pointing at its own ancestor makes the walk below unbounded, and this
     * repo has already been bitten once by assuming a project tree is acyclic
     * (`File.walkTopDown` following the toolchains' symlinks and reporting Node
     * at 184 MB). Twelve is past any real package.
     */
    private const val MAX_COMPACTED = 12

    /**
     * The children of [node], with single-child directory chains folded up.
     *
     * `src/main/java/com/example/tabscheck/MainActivity.java` is six taps and
     * six indentation levels to reach one file, and five of those rows say
     * nothing: each has exactly one thing in it. Every desktop IDE compacts
     * them, and on a phone -- where the pane is a drawer and the indent is a
     * fifth of the width by the time it arrives -- it matters more, not less.
     * The chain collapses to `src/main` and `java/com/example/tabscheck`: two
     * taps, two levels.
     *
     * It folds only where a directory holds *one visible* thing and that thing
     * is a directory, which is why the filtering happens before the count -- a
     * stray `.DS_Store` beside `com` should not un-fold the package the user
     * sees as single-child.
     */
    fun childrenOf(node: FileNode): List<FileNode> =
        visibleEntries(node.file)
            .map { compacted(FileNode(it, it.isDirectory, node.depth + 1)) }

    /** The entries a tree row would show, sorted as it shows them. */
    private fun visibleEntries(directory: File): List<File> {
        val entries = directory.listFiles() ?: return emptyList()
        return entries
            .filter { it.name !in IGNORED && !it.isHidden }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** [node] with any run of single-child directories beneath it joined onto its name. */
    private fun compacted(node: FileNode): FileNode {
        if (!node.isDirectory) return node
        var deepest = node.file
        val name = StringBuilder(node.name)
        var folded = 0
        while (folded < MAX_COMPACTED) {
            // A link is where the walk stops: following one is how a tree with
            // a cycle in it becomes an infinite row of slashes.
            if (Files.isSymbolicLink(deepest.toPath())) break
            val only = visibleEntries(deepest).singleOrNull() ?: break
            if (!only.isDirectory || Files.isSymbolicLink(only.toPath())) break
            deepest = only
            name.append(File.separatorChar).append(only.name)
            folded++
        }
        return node.copy(file = deepest, name = name.toString())
    }

    fun rootOf(project: Project): FileNode =
        FileNode(project.rootDir, isDirectory = true, depth = 0)
}
