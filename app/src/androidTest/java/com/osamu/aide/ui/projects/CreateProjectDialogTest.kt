package com.osamu.aide.ui.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.osamu.aide.core.fs.MonoCollectionsApp
import com.osamu.aide.core.fs.NodeHttpServer
import com.osamu.aide.core.fs.ProjectTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every template the picker offers can actually be seen and picked.
 *
 * The ancestor of this test was written after C# shipped hanging off the right
 * edge of the dialog: four chips in a `Row` do not fit the width of a phone,
 * a `Row` does not wrap, and the last one was squeezed to a sliver nobody could
 * read or tap. **Every test passed**, because a test that wants a C# project
 * asks the repository for one rather than tapping a chip.
 *
 * The chips are gone -- the picker is a list of templates now -- but the defect
 * they demonstrated is not, and the new layout is a *worse* case for it: each
 * row is a variable-length objective sentence beside a radio button, which is
 * precisely the shape that has produced nine defects in this codebase. So the
 * assertions below are about the control, not the label.
 *
 * **Driven at 360 dp as well as the default**, because every instrumented test
 * runs at the emulator's width and the bottom dock's tabs looked correct there
 * while being broken on an ordinary small phone. All three pass at
 * `adb shell wm size 720x1600` with `wm density 320`; reset both afterwards
 * with `wm size reset` / `wm density reset`. Re-run it that way after touching
 * this layout -- nothing here does it automatically.
 */
class CreateProjectDialogTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * No row squeezed its radio button away.
     *
     * **The control, and not the label or the row.** Comparing a row's right
     * edge against the dialog can never fire: a `Row` clamps to the space it
     * has, so the edge lands exactly on the boundary whatever happens inside.
     * What a missing `weight(1f)` on the text actually does is let the text
     * measure at whatever width it wants and leave the radio button the
     * remainder, which is frequently nothing -- a settings `Switch` at
     * `Rect(0, 0, 0, 0)` is how this last presented.
     *
     * The radio buttons are a fixed-size control repeated once per row, so
     * every one of them should be exactly the size of every other. That is the
     * sibling comparison the rule asks for, with the whole catalog supplying
     * the siblings: the longest objective in it is measured against the
     * shortest, in the same layout, on every run.
     *
     * Unclipped bounds on purpose. The list scrolls, so rows below the fold are
     * composed and laid out but not displayed, and `assertIsDisplayed` would be
     * asking a different question than this one.
     */
    @Test
    fun no_template_row_squeezes_out_its_radio_button() {
        compose.setContent {
            CreateProjectDialog(onDismiss = {}, onCreate = { _, _ -> })
        }

        val radios = compose.onAllNodesWithTag(TEMPLATE_RADIO_TAG, useUnmergedTree = true)
        val count = radios.fetchSemanticsNodes().size
        assertEquals(
            "the picker did not lay out one row per template",
            ProjectTemplate.ALL.size,
            count,
        )

        val sizes = (0 until count).map { index ->
            val bounds = radios[index].getUnclippedBoundsInRoot()
            ProjectTemplate.ALL[index].id to
                ((bounds.right - bounds.left) to (bounds.bottom - bounds.top))
        }

        assertEquals(
            "a radio button was measured in whatever the objective text left over, " +
                "so its row's text is missing Modifier.weight(1f): $sizes",
            1,
            sizes.map { it.second }.distinct().size,
        )
        // Zero-width is the degenerate case of the above and is worth naming
        // separately, because a layout where *every* control collapsed would
        // pass the comparison test with flying colours.
        assertTrue("every radio button collapsed: $sizes", sizes.first().second.first.value > 0f)
    }

    /**
     * Every template is reachable, including the last one.
     *
     * The list is taller than the dialog, so this is the assertion that the
     * bottom of it is not simply unreachable -- which is what a `Column` with a
     * `heightIn` and no `verticalScroll` would produce, and it looks exactly
     * like a shorter catalog.
     */
    @Test
    fun every_template_can_be_scrolled_to_and_read() {
        compose.setContent {
            CreateProjectDialog(onDismiss = {}, onCreate = { _, _ -> })
        }

        // `performScrollTo` on the target, not `performScrollToNode` on the
        // container: the picker is a `Column` with `verticalScroll`, which
        // publishes `ScrollBy` and a `VerticalScrollAxisRange` but **no
        // `ScrollToIndex`** -- that is a lazy list's semantics. Asking the
        // container to scroll to a node therefore finds no node with the
        // action and fails with a matcher dump that says nothing about why.
        //
        // Grouped by objective, because **two templates may share one**: the
        // Java and Kotlin basic apps have the same purpose and differ only in
        // language, and so do the Node and Python scripts. They are told apart
        // in the dialog by the language heading above them, not by this line,
        // so matching by text finds two nodes and a single-node matcher throws.
        ProjectTemplate.ALL.groupBy { it.objective }.forEach { (objective, sharing) ->
            val rows = compose.onAllNodesWithText(objective)
            assertEquals(
                "the picker does not show one row per template for: $objective",
                sharing.size,
                rows.fetchSemanticsNodes().size,
            )
            sharing.indices.forEach { index ->
                rows[index].performScrollTo().assertIsDisplayed()
            }
        }
    }

    /**
     * **One heading per language, and it names a language once.**
     *
     * The assertion that was missing, and the defect it would have caught
     * shipped: the catalog interleaved Java and Kotlin, the dialog emitted a
     * heading whenever the language *changed*, and the picker read
     * JAVA / KOTLIN / JAVA / KOTLIN -- four headings for two languages. Every
     * other test here passed, because they count rows and scroll to
     * objectives, and a heading is neither of those.
     *
     * Opening the dialog on a device is what showed it, which is this repo's
     * most reliable finding about itself.
     */
    @Test
    fun each_language_is_headed_exactly_once() {
        compose.setContent {
            CreateProjectDialog(onDismiss = {}, onCreate = { _, _ -> })
        }

        val languages = ProjectTemplate.ALL.map { it.language }.distinct()
        languages.forEach { language ->
            val heading = language.displayName.uppercase()
            val found = compose.onAllNodesWithText(heading).fetchSemanticsNodes().size
            assertEquals(
                "the picker heads $heading $found times; a language is one section, " +
                    "so a repeat means the catalog is interleaved or the dialog is " +
                    "heading on change instead of grouping",
                1,
                found,
            )
        }

        // And no language is headed that has no templates under it.
        assertEquals(
            "a language appears in the catalog more than once as a group",
            languages.size,
            languages.distinct().size,
        )
    }

    /**
     * The selected template says what it will write, and only the selected one.
     *
     * The preview is produced by running the template into a scratch directory
     * rather than from a list each template also declares, so
     * `TemplatePreviewTest` is what proves it *matches* `write`. This is the
     * other half: that the dialog shows it at all, shows it for the right
     * template, and does not leave the previous one's files on screen when the
     * selection moves -- which would be worse than showing none, since the
     * paths look plausible either way.
     *
     * `waitUntil` rather than `waitForIdle`: the preview is computed on the IO
     * dispatcher, which is not driven by the test clock, so an idle check
     * passes before the files arrive.
     */
    @Test
    fun the_selected_template_shows_the_files_it_will_write() {
        compose.setContent {
            CreateProjectDialog(onDismiss = {}, onCreate = { _, _ -> })
        }
        compose.onNodeWithText("Project name").performTextInput("Widgets")

        fun previewedPaths(): List<String> = compose
            .onAllNodesWithTag(TEMPLATE_FILE_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            }

        // The first template is selected by default, so its files appear
        // without anything being tapped.
        compose.waitUntil(10_000) { previewedPaths().isNotEmpty() }
        val first = previewedPaths()
        assertTrue(
            "the package directory does not follow the typed name: $first",
            first.any { "com/example/widgets" in it },
        )
        assertTrue(
            "the manifest is not previewed: $first",
            first.any { it == "src/main/AndroidManifest.xml" },
        )

        // Moving the selection replaces the list rather than adding to it.
        compose.onNodeWithText(NodeHttpServer.objective).performScrollTo().performClick()
        compose.waitUntil(10_000) { previewedPaths().any { it.endsWith(".js") } }

        val second = previewedPaths()
        assertTrue(
            "the Node template previews its own entry point: $second",
            second.any { it == "server.js" },
        )
        assertTrue(
            "the previous template's files are still shown: $second",
            second.none { it.startsWith("src/main/") },
        )
    }

    /** And picking one is what the dialog reports back. */
    @Test
    fun the_selected_template_is_what_gets_created() {
        var created: Pair<String, ProjectTemplate>? = null
        compose.setContent {
            CreateProjectDialog(
                onDismiss = {},
                onCreate = { name, template -> created = name to template },
            )
        }

        compose.onNodeWithText("Project name").performTextInput("Sharp Thing")
        // The last template in the catalog, so this also fails if the list
        // stopped scrolling before its end.
        compose.onNodeWithText(MonoCollectionsApp.objective)
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Create").performClick()

        assertEquals("Sharp Thing" to MonoCollectionsApp, created)
    }
}
