package com.osamu.aide.lsp.java

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ErroneousTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees

/**
 * The innermost tree the cursor is sitting in.
 *
 * Only the node kinds a completion can be asked from are overridden; anything
 * else falls through to the enclosing compilation unit, which is the right
 * answer for "complete a bare identifier at top level".
 *
 * Adapted from AndroidIDE's `FindCompletionsAt` (GPL-3.0, as is this project);
 * the position arithmetic below is theirs and is not obvious:
 *
 * - A member select starts *after* the dot, so its range begins at the end of
 *   its own expression plus one. Using the node's own start position would
 *   claim `activity` when the cursor is in `activity.|`.
 * - [visitErroneous] has to descend by hand. An unfinished expression is
 *   exactly what a completion request looks like, and javac hangs the salvaged
 *   subtrees off an [ErroneousTree] that the default scanner walks straight
 *   past.
 */
internal class FindCursor(private val task: JavacTask) : TreePathScanner<TreePath, Long>() {

    private var root: CompilationUnitTree? = null

    override fun visitCompilationUnit(node: CompilationUnitTree, find: Long): TreePath? {
        root = node
        return reduce(super.visitCompilationUnit(node, find), currentPath)
    }

    override fun visitIdentifier(node: IdentifierTree, find: Long): TreePath? =
        if (spans(node, find)) currentPath else super.visitIdentifier(node, find)

    override fun visitMemberSelect(node: MemberSelectTree, find: Long): TreePath? {
        val positions = Trees.instance(task).sourcePositions
        val start = positions.getEndPosition(root, node.expression) + 1
        val end = positions.getEndPosition(root, node)
        return if (find in start..end) currentPath else super.visitMemberSelect(node, find)
    }

    override fun visitImport(node: ImportTree, find: Long): TreePath? =
        if (spans(node.qualifiedIdentifier, find)) currentPath else super.visitImport(node, find)

    override fun visitErroneous(node: ErroneousTree, find: Long): TreePath? =
        node.errorTrees?.firstNotNullOfOrNull { scan(it, find) }

    private fun spans(node: Tree, find: Long): Boolean {
        val positions = Trees.instance(task).sourcePositions
        return find in positions.getStartPosition(root, node)..positions.getEndPosition(root, node)
    }

    /** First match wins; the scan reaches the innermost node first. */
    override fun reduce(a: TreePath?, b: TreePath?): TreePath? = a ?: b
}
