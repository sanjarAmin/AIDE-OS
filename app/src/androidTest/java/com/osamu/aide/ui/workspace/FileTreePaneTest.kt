package com.osamu.aide.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * What the file tree draws beyond the names.
 *
 * The pane is the surface a phone user spends the most taps in, and three of
 * the things it now says are easy to break without any test noticing: the
 * project's own folder is not a row, an unsaved tab carries a marker, and the
 * marker survives a long file name.
 *
 * That last one is this codebase's commonest defect -- a `Row` squeezes rather
 * than overflows -- and it is asserted the way `CreateProjectDialogTest`
 * established: **against the same control in a row with a short name**, never
 * against the container, since a `Row` never reports bounds past its own edge.
 */
class FileTreePaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val root = File("/projects/Demo")
    private val app = File(root, "app")
    private val shortFile = File(app, "A.java")
    private val longFile = File(app, "AnExtremelyLongFileNameThatGoesOnAndOnForever.java")

    private fun nodes() = listOf(
        FileNode(root, isDirectory = true, depth = 0),
        FileNode(app, isDirectory = true, depth = 1),
        FileNode(shortFile, isDirectory = false, depth = 2),
        FileNode(longFile, isDirectory = false, depth = 2),
    )

    private fun paneAt(
        width: Int,
        dirty: Set<String> = setOf(shortFile.absolutePath, longFile.absolutePath),
    ) = compose.setContent {
        Box(Modifier.width(width.dp)) {
            FileTreePane(
                projectName = "Demo",
                language = SourceLanguage.JAVA,
                nodes = nodes(),
                expandedPaths = setOf(root.absolutePath, app.absolutePath),
                selected = shortFile,
                openPaths = setOf(shortFile.absolutePath, longFile.absolutePath),
                dirtyPaths = dirty,
                onNodeClick = {},
                onCollapseAll = {},
            )
        }
    }

    /**
     * The header names the project, and the tree does not name it again.
     *
     * On a phone the drawer covers the app bar, so the name has to be here --
     * but it was here *twice*, as a header and as the first row of the tree,
     * on a pane where a wasted line costs a file.
     */
    @Test
    fun the_project_is_named_once() {
        paneAt(width = 360)

        assertEquals(1, compose.onAllNodesWithTextCount("Demo"))
        compose.onNodeWithText("app").assertIsDisplayed()
    }

    /** An unsaved tab is marked, and a saved one is not. */
    @Test
    fun only_unsaved_files_carry_a_marker() {
        paneAt(width = 360, dirty = setOf(longFile.absolutePath))

        assertEquals(1, compose.onAllNodesWithContentDescriptionCount("Unsaved changes"))
    }

    /**
     * **The marker is not squeezed out by a long name**, at the width of an
     * ordinary small phone.
     *
     * Compared against the marker in a row whose name is four characters: if
     * the name were unweighted it would measure at whatever width it wants and
     * the marker would be laid out in what is left, which is nothing.
     */
    @Test
    fun a_long_name_does_not_squeeze_the_unsaved_marker() {
        paneAt(width = 360)

        val markers = compose.onAllNodesWithContentDescription("Unsaved changes")
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
        assertEquals("expected a marker on both open files", 2, markers.size)
        val widths = markers.map { it.width }
        val heights = markers.map { it.height }
        assertTrue("the markers differ in width: $widths", widths[0] == widths[1])
        assertTrue("the markers differ in height: $heights", heights[0] == heights[1])
        assertTrue("a marker was measured at nothing: $widths", widths[0] > 0f)
    }

    /**
     * The header holds together in the **260 dp** pinned pane, which is the
     * narrowest place it is drawn -- narrower than any phone.
     *
     * The tell for a squeeze is height, not width: a badge with too little room
     * wraps "JAVA" and grows taller, and a button measured in what a long name
     * left over collapses to nothing. Both are checked against their own
     * geometry rather than against the pane, because a `Row` never reports
     * bounds past its own edge.
     */
    @Test
    fun the_header_survives_the_narrow_pane() {
        paneAt(width = 260)

        val badge = compose.onNodeWithText("JAVA").fetchSemanticsNode().boundsInRoot
        assertTrue("the language badge wrapped: ${badge.height} dp tall", badge.height < 60f)
        val button = compose.onNodeWithContentDescription("Collapse all folders")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("collapse was squeezed to ${button.width}", button.width > 60f)
        // A square button is one that was measured in the space it asked for.
        assertEquals(button.height, button.width, 2f)
        compose.onNodeWithText("Demo").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(
        text: String,
    ) = onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(
        description: String,
    ) = onAllNodesWithContentDescription(description).fetchSemanticsNodes().size
}
