package com.osamu.aide.ui.projects

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.osamu.aide.core.fs.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Every language the picker offers can actually be picked.
 *
 * Written after C# shipped hanging off the right edge of the dialog: four
 * chips in a `Row` do not fit the width of a phone, a `Row` does not wrap, and
 * the last one was clipped to a sliver nobody could read or tap. **Every test
 * passed**, because a test that wants a C# project asks the repository for one
 * rather than tapping a chip -- and the whole flow around it, the template,
 * the engine, the run, was correct. Only opening the dialog showed it.
 *
 * So this asserts the thing that was false: each chip is *displayed*, which in
 * Compose means laid out and not clipped away by an ancestor. A fifth language
 * added without room fails here rather than in someone's hands.
 */
class CreateProjectDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun no_language_chip_is_squeezed_out_of_shape() {
        compose.setContent {
            CreateProjectDialog(onDismiss = {}, onCreate = { _, _ -> })
        }

        // **Height, not position.** The instinct is to assert the last chip
        // does not hang off the right edge, and that assertion can never fire:
        // a `Row` does not overflow, it *squeezes*. Measured with the bug in
        // place, C# was 19 dp wide against JavaScript's 99, its right edge
        // exactly on the boundary, and `assertIsDisplayed` cheerfully true --
        // three ways of asking that all said the layout was fine.
        //
        // What gave it away was the height. A chip too narrow for its label
        // wraps the label, and a wrapped label makes the chip taller: 40.4 dp
        // against the others' 32. So the invariant is that every chip is the
        // same height as every other, which is exactly what "each one got the
        // width its text needed" looks like from outside.
        val heights = SourceLanguage.entries.filter { it in OFFERED }.associate { language ->
            val bounds = compose.onNodeWithText(language.displayName)
                .getUnclippedBoundsInRoot()
            language.displayName to (bounds.bottom - bounds.top)
        }

        assertEquals(
            "a chip taller than the rest wrapped its label, so it was too narrow: $heights",
            1,
            heights.values.distinct().size,
        )
    }

    /** And picking the last of them is what the dialog reports back. */
    @Test
    fun the_last_chip_is_tappable_and_is_what_gets_created() {
        var created: Pair<String, SourceLanguage>? = null
        compose.setContent {
            CreateProjectDialog(
                onDismiss = {},
                onCreate = { name, language -> created = name to language },
            )
        }

        compose.onNodeWithText("Project name").performTextInput("Sharp Thing")
        compose.onNodeWithText(SourceLanguage.CSHARP.displayName).performClick()
        compose.onNodeWithText("Create").performClick()

        assertEquals("Sharp Thing" to SourceLanguage.CSHARP, created)
    }

    private companion object {
        /**
         * What the picker offers. C and C++ are deliberately absent: a JNI
         * project is a Java app with native sources beside it, so it is created
         * as Java and gains `src/main/cpp` afterwards.
         */
        val OFFERED = setOf(
            SourceLanguage.JAVA,
            SourceLanguage.KOTLIN,
            SourceLanguage.JAVASCRIPT,
            SourceLanguage.CSHARP,
        )
    }
}
