package com.osamu.aide.editor

import android.content.Context
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.line.LineBackground
import io.github.rosemoe.sora.lang.styling.line.LineGutterBackground
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * The editor, with breakpoints in its gutter and the line a debugger stopped
 * on picked out.
 *
 * **The marks are re-applied every time highlighting replaces the styles.**
 * sora draws per-line styles out of the same `Styles` object the language's
 * analyzer produces, and the analyzer replaces that object -- or rewrites part
 * of it -- on every edit. A breakpoint added once would vanish the next time
 * the user typed, and come back never. Both routes an analyzer uses,
 * [setStyles] and [updateStyles], end here, so this is the one place that sees
 * every replacement.
 *
 * **A tinted line number rather than an icon.** sora can draw a side icon, but
 * its icon column exists only while at least one icon does: setting the first
 * breakpoint would widen the gutter and shift every line of code sideways, and
 * clearing the last would shift it back. A background on the number the user
 * just tapped moves nothing.
 *
 * Lines here are **1-based**, as a debugger and a person count them. sora's
 * are 0-based; the conversion happens once, below.
 */
class DebugGutterEditor(context: Context) : CodeEditor(context) {

    var breakpointLines: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            reapply()
        }

    var executionLine: Int? = null
        set(value) {
            if (field == value) return
            field = value
            reapply()
        }

    /**
     * Lines a write from outside the editor just changed, 1-based.
     *
     * The assistant edits files behind an approval gate, and until now the
     * reloaded buffer simply appeared with nothing saying which part of it
     * moved. These are painted and then faded out by [changedFade].
     */
    var changedLines: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            reapply()
        }

    /**
     * How strongly to paint [changedLines]: 1 at the moment of the edit, 0 once
     * the highlight has faded.
     *
     * Driven from Compose rather than by a timer in here, so the animation
     * obeys the platform's animator scale -- a device with animations turned
     * off gets no fade rather than a fade nobody asked for.
     */
    var changedFade: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            // Quantised: this is set on every animation frame, and a repaint of
            // the whole document sixty times a second for a colour change the
            // eye cannot resolve is wasted work on a phone.
            if (kotlin.math.abs(clamped - field) < FADE_STEP && clamped != 0f && clamped != 1f) return
            field = clamped
            reapply()
        }

    /** Lines (0-based) this has marked in the current styles, so they can be unmarked. */
    private val marked = mutableSetOf<Int>()

    override fun setStyles(styles: Styles?) {
        // A new object carries none of the old marks, so none are left to erase.
        marked.clear()
        styles?.let(::mark)
        super.setStyles(styles)
    }

    override fun updateStyles(styles: Styles, range: StyleUpdateRange?) {
        mark(styles)
        super.updateStyles(styles, range)
    }

    private fun reapply() {
        val styles = styles ?: return
        mark(styles)
        invalidate()
    }

    private fun mark(styles: Styles) {
        marked.forEach { line ->
            styles.eraseLineStyle(line, LineGutterBackground::class.java)
            styles.eraseLineStyle(line, LineBackground::class.java)
        }
        marked.clear()
        breakpointLines.forEach { line ->
            val index = line - 1
            if (index < 0) return@forEach
            styles.addLineStyle(LineGutterBackground(index, ConstColor(BREAKPOINT_GUTTER)))
            marked += index
        }
        if (changedFade > 0f) {
            val alpha = (CHANGED_MAX_ALPHA * changedFade).toInt().coerceIn(0, 255)
            changedLines.forEach { line ->
                val index = line - 1
                if (index < 0) return@forEach
                // Under the debugger's marks on purpose: where execution is
                // stopped outranks what an edit touched a moment ago.
                if (index in marked) return@forEach
                styles.addLineStyle(LineBackground(index, ConstColor((alpha shl 24) or CHANGED_RGB)))
                marked += index
            }
        }

        executionLine?.let { line ->
            val index = line - 1
            if (index < 0) return@let
            styles.addLineStyle(LineBackground(index, ConstColor(EXECUTION_LINE)))
            // The stopped line's number goes amber too, even under a
            // breakpoint's red: where execution *is* matters more than where
            // it was asked to stop, and the row background alone is easy to
            // miss beside a selection highlight.
            styles.eraseLineStyle(index, LineGutterBackground::class.java)
            styles.addLineStyle(LineGutterBackground(index, ConstColor(EXECUTION_GUTTER)))
            marked += index
        }
    }

    private companion object {
        // ARGB. Translucent so they read on both the light scheme and Darcula
        // without a pair of each.
        const val BREAKPOINT_GUTTER = 0x99E53935.toInt()
        const val EXECUTION_LINE = 0x40FFB300
        const val EXECUTION_GUTTER = 0xCCFFB300.toInt()

        /**
         * Green, and quiet at full strength.
         *
         * A changed line is information, not an alarm: it has to be visible
         * over both colour schemes without competing with a selection or with
         * the amber of a stopped line. 0x4D is about 30%.
         */
        const val CHANGED_RGB = 0x34D399
        const val CHANGED_MAX_ALPHA = 0x4D

        /** Repaint only when the fade has moved enough to see. */
        const val FADE_STEP = 0.04f
    }
}
