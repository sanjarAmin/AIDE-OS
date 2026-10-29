package com.osamu.aide.ui.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The project grid on a tablet: rows of cards that line up as columns.
 *
 * **The defect this pins is a short last row.** With three projects in two
 * columns, the third sits alone; weighted only by what is present, it would
 * stretch across both columns and draw twice as wide as the cards above it.
 * Nothing about that card is wrong on its own -- it is laid out, displayed and
 * clickable -- so, as CLAUDE.md says of squeezed rows, the assertion has to
 * compare it with its siblings rather than with anything absolute.
 *
 * The width is fixed at 700 dp so the grid has two columns whatever the device
 * running the test is.
 */
class ProjectGridTest {

    @get:Rule
    val compose = createComposeRule()

    private fun project(name: String) = Project(
        name = name,
        rootDir = File("/tmp/grid/$name"),
        applicationId = "com.example.grid",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 1L,
    )

    @Test
    fun a_lone_card_in_the_last_row_keeps_the_column_width_and_the_row_height() {
        val projects = listOf(
            project("alpha"),
            // Long enough to wrap if a card let it, which would make its row
            // taller than the one below and step the grid.
            project("a project name far too long to fit on one line of a card"),
            project("gamma"),
        )
        compose.setContent {
            Column(Modifier.width(700.dp)) {
                projects.chunked(2).forEach { row ->
                    ProjectCardRow(
                        projects = row,
                        columns = 2,
                        sizeOf = { 1024L },
                        onOpen = {},
                        onDelete = {},
                    )
                }
            }
        }

        val cards = compose.onAllNodesWithTag(PROJECT_CARD_TAG).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("expected three cards", 3, cards.size)
        val (first, second, lone) = cards

        assertEquals("the lone card is not one column wide: $lone against $first", first.width, lone.width, TOLERANCE)
        assertEquals("the two columns differ: $first against $second", first.width, second.width, TOLERANCE)
        assertEquals("the lone card is not under the first column", first.left, lone.left, TOLERANCE)
        assertEquals("a long name changed its card's height: $second against $first", first.height, second.height, TOLERANCE)
        assertEquals("the last row is a different height: $lone against $first", first.height, lone.height, TOLERANCE)
    }

    private companion object {
        /** Half a pixel of rounding between two weighted children. */
        const val TOLERANCE = 1f
    }
}
