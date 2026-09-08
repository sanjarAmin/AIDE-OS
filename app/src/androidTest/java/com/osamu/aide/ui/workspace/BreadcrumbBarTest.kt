package com.osamu.aide.ui.workspace

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The breadcrumb's segments act.
 *
 * Its KDoc described an "interactive breadcrumb bar" and nothing was wired to
 * it: the bar rendered, tapping a segment did nothing, and the claim read as
 * true to anyone who did not tap. That is the same shape as `EditorTheme`
 * saying colours "resolve to the app's theme" over an editor that was always
 * white, and it is the shape this test exists to stop -- so it asserts on the
 * callback, which is the only part a rendering cannot fake.
 */
@RunWith(AndroidJUnit4::class)
class BreadcrumbBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val root = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "breadcrumb/Project",
    )
    private val file = File(root, "src/main/java/com/example/App.java")

    private fun show(onSegmentClick: (File) -> Unit) {
        compose.setContent {
            BreadcrumbBar(file = file, projectRoot = root, onSegmentClick = onSegmentClick)
        }
    }

    @Test
    fun a_directory_segment_names_the_directory_it_stands_for() {
        var revealed: File? = null
        show { revealed = it }

        compose.onNodeWithText("example").performClick()

        // Not `<root>/example`: the segments are a path, and the third one
        // means the third level down, not a child of the root.
        assertEquals(File(root, "src/main/java/com/example"), revealed)
    }

    @Test
    fun the_file_segment_reveals_the_directory_holding_it() {
        var revealed: File? = null
        show { revealed = it }

        compose.onNodeWithText("App.java").performClick()

        // Expanding a file is meaningless; what the user wants from the last
        // segment is the file's neighbours.
        assertEquals(file.parentFile, revealed)
    }

    @Test
    fun the_first_segment_is_a_child_of_the_project_root() {
        var revealed: File? = null
        show { revealed = it }

        compose.onNodeWithText("src").performClick()

        assertEquals(File(root, "src"), revealed)
    }
}
