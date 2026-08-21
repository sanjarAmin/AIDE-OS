package com.osamu.aide.core.ui.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneBreakpointsTest {

    @Test
    fun `phone widths get a single pane`() {
        assertEquals(PaneMode.SINGLE, PaneBreakpoints.forWidth(360.dp))
        assertEquals(PaneMode.SINGLE, PaneBreakpoints.forWidth(599.dp))
    }

    @Test
    fun `foldable and small tablet widths pin the file tree`() {
        assertEquals(PaneMode.DUAL, PaneBreakpoints.forWidth(600.dp))
        assertEquals(PaneMode.DUAL, PaneBreakpoints.forWidth(999.dp))
    }

    @Test
    fun `tablet landscape widths show all three panes`() {
        assertEquals(PaneMode.TRIPLE, PaneBreakpoints.forWidth(1000.dp))
        assertEquals(PaneMode.TRIPLE, PaneBreakpoints.forWidth(1600.dp))
    }

    @Test
    fun `pane visibility flags follow the mode`() {
        assertEquals(false, PaneMode.SINGLE.showsFileTree)
        assertEquals(true, PaneMode.DUAL.showsFileTree)
        assertEquals(false, PaneMode.DUAL.showsToolPane)
        assertEquals(true, PaneMode.TRIPLE.showsToolPane)
    }
}
