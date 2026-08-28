package com.osamu.aide.ui.workspace

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The one tap in "one-tap error fix".
 *
 * The message itself is pinned by `FixRequestTest`; what this covers is that
 * the affordance is reachable from where the user is actually looking -- the
 * Problems list -- and that it hands over the diagnostic that was tapped rather
 * than whichever one happens to be first.
 */
class FixAffordanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val cannotFindSymbol = Diagnostic(
        severity = DiagnosticSeverity.ERROR,
        message = "cannot find symbol: class Widget",
        file = File("/projects/Demo/src/Main.java"),
        line = 12,
        column = 5,
    )

    private val unusedImport = Diagnostic(
        severity = DiagnosticSeverity.WARNING,
        message = "unused import: java.util.List",
        file = File("/projects/Demo/src/Other.java"),
        line = 3,
        column = 1,
    )

    private fun showProblems(
        problems: List<Diagnostic>,
        onFix: (Diagnostic) -> Unit,
    ) {
        compose.setContent {
            BottomToolDock(
                buildState = BuildUiState(),
                problems = problems,
                // This test is about the Problems tab. The git tab is passed
                // its empty state rather than being given a default in the
                // dock, so a real caller that forgets to wire it fails to
                // compile instead of silently showing an empty panel.
                gitState = GitUiState(),
                gitActions = GitActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}),
                onDiagnosticClick = {},
                onFixDiagnostic = onFix,
                onLaunchIntent = {},
                onClose = {},
            )
        }
        compose.onNodeWithText("Problems").performClick()
    }

    @Test
    fun every_problem_offers_a_fix() {
        showProblems(listOf(cannotFindSymbol, unusedImport)) {}

        assertEquals(
            2,
            compose.onAllNodesWithContentDescription("Ask the assistant to fix this")
                .fetchSemanticsNodes().size,
        )
    }

    /** The one that was tapped, not the first one in the list. */
    @Test
    fun the_tapped_problem_is_the_one_handed_over() {
        val asked = mutableListOf<Diagnostic>()
        showProblems(listOf(cannotFindSymbol, unusedImport)) { asked += it }

        compose.onAllNodesWithContentDescription("Ask the assistant to fix this")[1].performClick()

        assertEquals(listOf(unusedImport), asked)
    }

    /**
     * And what it asks is usable.
     *
     * The join between the two halves -- the row and the message -- is worth one
     * assertion, because a fix button wired to the right diagnostic and the
     * wrong project root produces an absolute path the assistant's tools refuse.
     */
    @Test
    fun the_message_the_tap_produces_names_the_file_relatively() {
        val asked = mutableListOf<Diagnostic>()
        showProblems(listOf(cannotFindSymbol)) { asked += it }

        compose.onAllNodesWithContentDescription("Ask the assistant to fix this")[0].performClick()

        val request = fixRequest(asked.single(), File("/projects/Demo"))
        assertTrue(request, "src/Main.java:12:5" in request)
        assertTrue(request, "cannot find symbol: class Widget" in request)
    }
}
