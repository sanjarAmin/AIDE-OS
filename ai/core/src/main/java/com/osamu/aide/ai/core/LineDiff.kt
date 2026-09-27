package com.osamu.aide.ai.core

/**
 * One row of the change an approval prompt is asking about.
 *
 * Structured rather than a unified-diff string, and that is the point of it.
 * The prompt used to be handed the new file's text and colour any line starting
 * with `-` as a deletion -- so a YAML list, a `--flag` or a negative number read
 * as removed, while nothing actually being removed was shown at all. A row that
 * says what it is cannot be misread by what its text happens to begin with.
 */
data class DiffLine(
    val kind: Kind,
    val text: String,
    /** 1-based, in the file as it will be. Null for a removed line and a gap. */
    val number: Int?,
) {
    enum class Kind { CONTEXT, ADDED, REMOVED, GAP }
}

/**
 * The difference between a file and what the assistant wants to replace it with.
 *
 * `edit_file` rewrites the whole file, so what the person approving needs is
 * not the new text -- they would have to hold the old one in their head to
 * compare -- but the lines that change, with enough around them to place them.
 */
object LineDiff {

    /**
     * [old] is null when the file does not exist yet: every line is an addition.
     *
     * Empty when nothing changes, which is its own answer: the model is about
     * to rewrite a file with the text it already has.
     */
    fun of(
        old: String?,
        new: String,
        context: Int = 3,
        maxRows: Int = 400,
    ): List<DiffLine> {
        val b = split(new)
        if (old == null) {
            return cap(b.mapIndexed { i, line -> DiffLine(DiffLine.Kind.ADDED, line, i + 1) }, maxRows)
        }
        val a = split(old)
        val ops = operations(a, b)
        if (ops.none { it.kind != DiffLine.Kind.CONTEXT }) return emptyList()
        return cap(hunks(ops, context), maxRows)
    }

    /** Lines without their terminators; a final newline does not make a line. */
    private fun split(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }

    /**
     * Every line of both files, in order, as kept, removed or added.
     *
     * The common head and tail are matched first, because an edit usually
     * touches a few lines in the middle of a long file and those are free. The
     * middle gets a longest-common-subsequence match when it is small enough
     * that the table fits comfortably on a phone; past that it is shown as
     * replaced wholesale, which is honest if not minimal, rather than the
     * approval freezing while it computes.
     */
    private fun operations(a: List<String>, b: List<String>): List<DiffLine> {
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head &&
            a[a.size - 1 - tail] == b[b.size - 1 - tail]
        ) tail++

        val ops = mutableListOf<DiffLine>()
        for (i in 0 until head) ops += DiffLine(DiffLine.Kind.CONTEXT, b[i], i + 1)

        val aMid = a.subList(head, a.size - tail)
        val bMid = b.subList(head, b.size - tail)
        if (aMid.size.toLong() * bMid.size <= MAX_TABLE_CELLS) {
            middleByLcs(aMid, bMid, head, ops)
        } else {
            aMid.forEach { ops += DiffLine(DiffLine.Kind.REMOVED, it, null) }
            bMid.forEachIndexed { i, line -> ops += DiffLine(DiffLine.Kind.ADDED, line, head + i + 1) }
        }

        for (i in b.size - tail until b.size) ops += DiffLine(DiffLine.Kind.CONTEXT, b[i], i + 1)
        return ops
    }

    private fun middleByLcs(a: List<String>, b: List<String>, offset: Int, ops: MutableList<DiffLine>) {
        val n = a.size
        val m = b.size
        // lcs[i][j]: the longest common subsequence of a[i..] and b[j..].
        val width = m + 1
        val lcs = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i * width + j] = if (a[i] == b[j]) {
                    lcs[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[i] == b[j] -> {
                    ops += DiffLine(DiffLine.Kind.CONTEXT, b[j], offset + j + 1); i++; j++
                }
                // Removals before additions at a tie, the order a reader
                // expects: what goes, then what replaces it.
                j == m || (i < n && lcs[(i + 1) * width + j] >= lcs[i * width + j + 1]) -> {
                    ops += DiffLine(DiffLine.Kind.REMOVED, a[i], null); i++
                }
                else -> {
                    ops += DiffLine(DiffLine.Kind.ADDED, b[j], offset + j + 1); j++
                }
            }
        }
    }

    /** The changed rows with [context] lines either side; the rest folds into gaps. */
    private fun hunks(ops: List<DiffLine>, context: Int): List<DiffLine> {
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { index, op ->
            if (op.kind != DiffLine.Kind.CONTEXT) {
                for (k in maxOf(0, index - context)..minOf(ops.lastIndex, index + context)) keep[k] = true
            }
        }
        val rows = mutableListOf<DiffLine>()
        var skipped = 0
        ops.forEachIndexed { index, op ->
            if (keep[index]) {
                if (skipped > 0) rows += gap(skipped)
                skipped = 0
                rows += op
            } else {
                skipped++
            }
        }
        if (skipped > 0) rows += gap(skipped)
        return rows
    }

    private fun gap(lines: Int) = DiffLine(
        DiffLine.Kind.GAP,
        if (lines == 1) "1 unchanged line" else "$lines unchanged lines",
        null,
    )

    private fun cap(rows: List<DiffLine>, maxRows: Int): List<DiffLine> =
        if (rows.size <= maxRows) {
            rows
        } else {
            rows.take(maxRows) + DiffLine(DiffLine.Kind.GAP, "${rows.size - maxRows} more lines not shown", null)
        }

    /** A million ints is 4 MB: fine on any phone this runs on, and fast. */
    private const val MAX_TABLE_CELLS = 1_000_000L
}
