package com.osamu.aide.editor

/**
 * Which lines of [after] are not in [before], for highlighting a file that
 * changed under the editor.
 *
 * **Not a real diff, and deliberately so.** It trims the common head and the
 * common tail and calls everything between them changed. That is exactly right
 * for the case it exists for -- an assistant rewriting a region of a file -- and
 * it is wrong in the way every cheap diff is wrong: two separate edits far
 * apart mark everything between them as well. A proper LCS would fix that, and
 * would also be a hundred lines of code and a per-keystroke cost, to sharpen a
 * highlight that fades after two seconds.
 *
 * Lines are **1-based**, as the editor's gutter and a person count them.
 *
 * Returns nothing when the texts are equal, which is the common case: a write
 * that changed nothing should not flash the whole file.
 */
fun changedLines(before: String, after: String): Set<Int> {
    if (before == after) return emptySet()

    val old = before.lines()
    val new = after.lines()

    var head = 0
    while (head < old.size && head < new.size && old[head] == new[head]) head++

    // Counted from the ends, and stopped before the head so a file that only
    // gained lines in the middle cannot count one line as both.
    var tail = 0
    while (
        tail < old.size - head &&
        tail < new.size - head &&
        old[old.size - 1 - tail] == new[new.size - 1 - tail]
    ) {
        tail++
    }

    val firstChanged = head
    val lastChanged = new.size - 1 - tail
    if (firstChanged > lastChanged) {
        // Only deletions: nothing in the new text is new, so there is nothing
        // to paint. Marking the join would point at a line that did not change.
        return emptySet()
    }
    return (firstChanged..lastChanged).mapTo(LinkedHashSet()) { it + 1 }
}
