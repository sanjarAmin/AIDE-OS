package com.osamu.aide.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How many panes the workspace can show at once.
 *
 * This is the phone/tablet split the whole IDE shell hangs off: on a phone the
 * file tree and tool panes are transient overlays, on a tablet they are
 * permanent columns beside the editor.
 */
enum class PaneMode {
    /** Phone portrait: editor only; tree and tools open as drawers/sheets. */
    SINGLE,

    /** Large phone landscape, small tablet: file tree pinned beside the editor. */
    DUAL,

    /** Tablet landscape: file tree, editor and tool pane all visible. */
    TRIPLE;

    val showsFileTree: Boolean get() = this != SINGLE
    val showsToolPane: Boolean get() = this == TRIPLE
}

object PaneBreakpoints {
    val Dual: Dp = 600.dp
    val Triple: Dp = 1000.dp

    fun forWidth(width: Dp): PaneMode = when {
        width >= Triple -> PaneMode.TRIPLE
        width >= Dual -> PaneMode.DUAL
        else -> PaneMode.SINGLE
    }
}

/**
 * Lays out the IDE shell's columns for a given [mode].
 *
 * [fileTree] and [toolPane] are only composed when [mode] shows them, so a phone
 * never pays to build panes it cannot display. The caller measures the width
 * (via [PaneBreakpoints.forWidth]) rather than this composable, because callers
 * also need the mode to decide between a drawer and a pinned column.
 */
@Composable
fun AdaptiveWorkspace(
    mode: PaneMode,
    modifier: Modifier = Modifier,
    fileTreeWidth: Dp = 260.dp,
    toolPaneWidth: Dp = 320.dp,
    fileTree: @Composable () -> Unit,
    toolPane: @Composable () -> Unit,
    editor: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        if (mode.showsFileTree) {
            Box(Modifier.width(fileTreeWidth).fillMaxHeight()) { fileTree() }
            VerticalDivider()
        }

        Box(Modifier.weight(1f).fillMaxHeight()) { editor() }

        if (mode.showsToolPane) {
            VerticalDivider()
            Box(Modifier.width(toolPaneWidth).fillMaxHeight()) { toolPane() }
        }
    }
}

/** Horizontal rule matching the workspace dividers, for use inside panes. */
@Composable
fun PaneDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier)
