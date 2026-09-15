package com.osamu.aide.ui.workspace

import java.io.File
import java.security.MessageDigest

/**
 * Where breakpoints go when the text around them changes.
 *
 * The editor reports a whole buffer per edit, not a range, so the edit is
 * recovered by comparing: lines equal from the start are untouched, lines
 * equal from the end moved by however many lines the edit added or removed,
 * and what lies between is what changed. A breakpoint in that middle stays
 * where it is while the middle still has a line there -- typing on the
 * breakpoint's own line, or pressing Enter in it -- and goes when its line was
 * deleted, which is what a desktop IDE does.
 *
 * Without this a line inserted above a breakpoint left it on the old number:
 * the gutter mark sat beside the wrong statement, and so did the debugger,
 * because both read the same set. `debugger/FINDINGS.md` section 15.
 */
object BreakpointLines {

    /** [lines], 1-based, after the buffer went from [before] to [after]. */
    fun followEdit(lines: Set<Int>, before: String, after: String): Set<Int> {
        if (lines.isEmpty() || before == after) return lines
        val old = before.split('\n')
        val new = after.split('\n')
        var prefix = 0
        val shortest = minOf(old.size, new.size)
        while (prefix < shortest && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < shortest - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++

        val oldEnd = old.size - suffix
        val newRegion = new.size - suffix - prefix
        return lines.mapNotNullTo(HashSet()) { line ->
            val index = line - 1
            when {
                index < prefix -> line
                index >= oldEnd -> line + (new.size - old.size)
                // In the changed part: kept while the new text still has a line
                // at the same offset into it.
                index - prefix < newRegion -> line
                else -> null
            }
        }
    }
}

/**
 * Breakpoints saved per project, so they survive the workspace closing.
 *
 * **In app storage, not in the project.** The project is usually a git
 * checkout, and a file of breakpoints in it is one more thing to ignore or, worse,
 * commit. Named by a hash of the project's path, one line per breakpoint:
 * the file relative to the project, a tab, the line.
 */
class BreakpointStore(private val dir: File) {

    fun load(projectRoot: File): Set<FileBreakpoint> = runCatching {
        fileFor(projectRoot).readLines().mapNotNullTo(HashSet()) { entry ->
            val (path, line) = entry.split('\t').takeIf { it.size == 2 } ?: return@mapNotNullTo null
            FileBreakpoint(File(projectRoot, path), line.toIntOrNull() ?: return@mapNotNullTo null)
        }
    }.getOrDefault(emptySet())

    fun save(projectRoot: File, breakpoints: Set<FileBreakpoint>) {
        runCatching {
            dir.mkdirs()
            val text = breakpoints
                .sortedWith(compareBy({ it.file.path }, { it.line }))
                .joinToString("") { "${it.file.relativeTo(projectRoot).invariantSeparatorsPath}\t${it.line}\n" }
            val partial = File(dir, fileFor(projectRoot).name + ".partial")
            partial.writeText(text)
            partial.renameTo(fileFor(projectRoot))
        }
    }

    private fun fileFor(projectRoot: File): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(projectRoot.absolutePath.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".breakpoints")
    }
}
