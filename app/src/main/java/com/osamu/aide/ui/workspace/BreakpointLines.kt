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
 * checkout, and a file of breakpoints in it is one more thing to ignore or,
 * worse, commit. Named by a hash of the project's path.
 *
 * **Each breakpoint is kept at two lines.** Its line in the editor's buffer,
 * and that line carried onto the file as it is on disk -- because the buffer
 * may not have been saved, and only the disk survives the app closing. With
 * the buffer's hash beside them, [load] can tell which applies: if the file
 * now hashes as the buffer did, the edit was saved and the buffer's line is
 * right; otherwise the disk's is. Keeping one number could not tell a saved
 * edit from an abandoned one, and put the breakpoint beside the wrong line
 * after either.
 */
class BreakpointStore(private val dir: File) {

    /** One breakpoint as saved: [line] in the buffer whose text hashed to [bufferHash], [diskLine] in the file. */
    data class Entry(val file: File, val line: Int, val diskLine: Int?, val bufferHash: String)

    fun load(projectRoot: File): Set<FileBreakpoint> = runCatching {
        val entries = fileFor(projectRoot).readLines().mapNotNull { row ->
            val f = row.split('\t')
            if (f.size != 4) return@mapNotNull null
            Entry(File(projectRoot, f[0]), f[1].toIntOrNull() ?: return@mapNotNull null, f[2].toIntOrNull(), f[3])
        }
        entries.groupBy { it.file }.flatMap { (file, inFile) ->
            val disk = runCatching { hash(file.readText()) }.getOrNull() ?: return@flatMap emptyList()
            inFile.mapNotNull { entry ->
                val line = if (entry.bufferHash == disk) entry.line else entry.diskLine
                line?.let { FileBreakpoint(file, it) }
            }
        }.toSet()
    }.getOrDefault(emptySet())

    fun save(projectRoot: File, entries: List<Entry>) {
        runCatching {
            dir.mkdirs()
            val text = entries
                .sortedWith(compareBy({ it.file.path }, { it.line }))
                .joinToString("") {
                    "${it.file.relativeTo(projectRoot).invariantSeparatorsPath}\t${it.line}\t${it.diskLine ?: "-"}\t${it.bufferHash}\n"
                }
            val partial = File(dir, fileFor(projectRoot).name + ".partial")
            partial.writeText(text)
            partial.renameTo(fileFor(projectRoot))
        }
    }

    /**
     * The entries for [breakpoints], given each file's buffer where the editor
     * holds one; a file without a buffer is its disk text.
     */
    fun entriesFor(breakpoints: Set<FileBreakpoint>, buffers: Map<File, String>): List<Entry> =
        breakpoints.groupBy { it.file }.flatMap { (file, inFile) ->
            val disk = runCatching { file.readText() }.getOrNull() ?: return@flatMap emptyList()
            val buffer = buffers[file] ?: disk
            inFile.map { breakpoint ->
                // Carried one at a time: followEdit drops a line that has no
                // place on disk, and which breakpoint it dropped matters here.
                val onDisk = if (buffer == disk) breakpoint.line
                else BreakpointLines.followEdit(setOf(breakpoint.line), buffer, disk).singleOrNull()
                Entry(file, breakpoint.line, onDisk, hash(buffer))
            }
        }

    private fun fileFor(projectRoot: File): File =
        File(dir, hash(projectRoot.absolutePath) + ".breakpoints")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
