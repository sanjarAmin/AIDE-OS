package com.osamu.aide.lsp.java

import com.osamu.aide.lsp.api.SourceLocation
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.TreePath
import java.io.File

/**
 * Finds where the thing under the cursor was declared.
 *
 * Only declarations that exist **as source** can be found. An element read out
 * of `android.jar` has a symbol but no tree, so `getPath` returns null and so
 * does this -- there is no file to open. Guessing at a location inside the
 * platform would send the user somewhere that does not exist.
 *
 * The range narrowing is adapted from AndroidIDE's `FindHelper.location`
 * (GPL-3.0, as is this project): javac's source positions for a declaration
 * span the whole thing, body included, so the name is located by searching the
 * declaration's own text for it.
 */
internal object JavaDefinitions {

    fun at(compilation: Compilation, cursor: TreePath, projectRoot: File): SourceLocation? {
        val element = compilation.trees.getElement(cursor) ?: return null
        val declaration = compilation.trees.getPath(element) ?: return null

        // A constructor is declared under the class's name, not under `<init>`,
        // which appears nowhere in the source text.
        val name = element.simpleName.toString().let {
            if (it == "<init>") element.enclosingElement.simpleName.toString() else it
        }

        val unit = declaration.compilationUnit
        val positions = compilation.trees.sourcePositions
        val start = positions.getStartPosition(unit, declaration.leaf).toInt()
        val end = positions.getEndPosition(unit, declaration.leaf).toInt()
        if (start < 0) return null

        val nameStart = findName(unit, name, start, end) ?: start
        val lines = unit.lineMap ?: return null

        val file = unit.sourceFile?.toUri()?.let { File(it.path) } ?: return null
        return SourceLocation(
            file = relativise(file, projectRoot),
            line = lines.getLineNumber(nameStart.toLong()).toInt(),
            column = lines.getColumnNumber(nameStart.toLong()).toInt(),
            endColumn = lines.getColumnNumber((nameStart + name.length).toLong()).toInt(),
        )
    }

    /**
     * The first occurrence of [name] inside the declaration's own span.
     *
     * Crude on purpose. A declaration's text begins with its modifiers,
     * annotations and return type, any of which could contain the name as a
     * substring -- but the identifier is a whole word, so the search is bounded
     * by non-identifier characters on both sides.
     */
    private fun findName(unit: CompilationUnitTree, name: String, start: Int, end: Int): Int? {
        val contents = runCatching { unit.sourceFile.getCharContent(true) }.getOrNull() ?: return null
        val limit = end.coerceAtMost(contents.length)
        if (start >= limit) return null

        var index = start
        while (index in start until limit) {
            val found = contents.indexOf(name, index, limit)
            if (found < 0) return null
            val before = contents.getOrNull(found - 1)
            val after = contents.getOrNull(found + name.length)
            val wholeWord = (before == null || !Character.isJavaIdentifierPart(before)) &&
                (after == null || !Character.isJavaIdentifierPart(after))
            if (wholeWord) return found
            index = found + name.length
        }
        return null
    }

    private fun CharSequence.indexOf(needle: String, from: Int, limit: Int): Int {
        val last = limit - needle.length
        for (i in from..last) {
            if (regionMatches(i, needle)) return i
        }
        return -1
    }

    private fun CharSequence.regionMatches(at: Int, needle: String): Boolean {
        for (i in needle.indices) if (this[at + i] != needle[i]) return false
        return true
    }

    private fun CharSequence.getOrNull(index: Int): Char? =
        if (index in indices) this[index] else null

    /** Canonicalised on both sides; see `JavacDiagnostics` for why that matters. */
    private fun relativise(file: File, projectRoot: File): File {
        val path = runCatching { file.canonicalFile }.getOrDefault(file).invariantSeparatorsPath
        val root = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)
        val prefix = root.invariantSeparatorsPath.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) File(path.removePrefix(prefix)) else file
    }
}
