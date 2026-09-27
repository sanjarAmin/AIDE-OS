package com.osamu.aide.ui.settings

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The screen that says what a gigabyte of downloads is made of.
 *
 * Instrumented and against the **real** storage root, because the thing worth
 * proving is that Remove deletes files off a device — a fake would prove a
 * lambda was called. The fixture uses an id no component claims, which is also
 * the case the listing exists for: something left behind that the app can no
 * longer describe but can still delete.
 */
class ToolchainSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()
    private val toolchain by lazy { ToolchainManager(context, dispatchers) }

    /** An id nothing in ToolchainComponent.ALL claims, so no real install is touched. */
    private val fixture: File
        get() = File(context.filesDir, "toolchains/zz-test-fixture")

    /**
     * A second one whose name is as long as a real component's.
     *
     * Nothing claims this id either, so the row shows the directory name --
     * which is the point: the longest label this screen ever renders is a
     * component name like "C/C++ toolchain (clang 21.1.8)", and the layout has
     * to survive it.
     */
    private val longNamed: File
        get() = File(context.filesDir, "toolchains/zz-a-toolchain-with-a-very-long-name-indeed")

    @Before
    fun setUp() {
        longNamed.deleteRecursively()
        stage(fixture)
    }

    /** 3 MB under [root], which is what the listing reports it as. */
    private fun stage(root: File) {
        root.deleteRecursively()
        File(root, "bin/thing").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3 * 1024 * 1024))
        }
    }

    @After
    fun tearDown() {
        fixture.deleteRecursively()
        longNamed.deleteRecursively()
    }

    /**
     * Remove is the same size whatever the name beside it.
     *
     * Its column had no `weight`, so a long name measured at whatever width it
     * wanted and the button was laid out in what remained -- collapsing the one
     * control that reclaims the space this screen exists to report. Asserted
     * against the sibling row rather than against the container, for the reason
     * `EditorSectionTest` and `CreateProjectDialogTest` both record: a `Row`
     * never reports bounds past its own edge, so only the two being different
     * sizes shows it.
     */
    @Test
    fun a_long_name_does_not_squeeze_remove_away() {
        // Staged here rather than in setUp: a second row carries the same size
        // text as the first, and the listing tests match that text uniquely.
        stage(longNamed)

        compose.setContent { ToolchainSection(toolchain, dispatchers) }
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithText("Remove").fetchSemanticsNodes().size >= 2
        }

        val short = removeButton(fixture.name)
        val long = removeButton(longNamed.name)

        assertEquals(
            "Remove is not the same width in both rows: $short against $long",
            short.width,
            long.width,
            0.5f,
        )
    }

    private fun removeButton(displayName: String) = compose
        .onNodeWithContentDescription("Remove $displayName")
        .fetchSemanticsNode()
        .boundsInRoot

    @Test
    fun it_lists_what_is_on_disk_with_its_size_and_removes_it() {
        compose.setContent { ToolchainSection(toolchain, dispatchers) }

        // Named by its directory, because nothing claims it -- and marked as
        // such, which is the whole reason a user would delete it.
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTextSafely("zz-test-fixture")
        }
        compose.onNodeWithText("3 MB · no longer used").assertExists()

        compose.onNodeWithContentDescription("Remove zz-test-fixture").performClick()
        // The confirmation says what it frees. A screen about disk space that
        // deletes without naming the number is asking for a blind tap.
        compose.onNodeWithText("Remove zz-test-fixture?").assertExists()
        assertTrue("it deleted before being confirmed", fixture.isDirectory)

        // Scoped to the dialog: the row's own "Remove" is still in the tree
        // behind it, and clicking the obscured one fails with "Failed to inject
        // touch input", which reads as a broken button rather than an
        // ambiguous match.
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) { !fixture.exists() }

        assertFalse("Remove left the files on disk", fixture.exists())
    }

    @Test
    fun keeping_it_changes_nothing() {
        compose.setContent { ToolchainSection(toolchain, dispatchers) }
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithTextSafely("zz-test-fixture")
        }

        compose.onNodeWithContentDescription("Remove zz-test-fixture").performClick()
        compose.onNode(hasText("Keep") and hasAnyAncestor(isDialog())).performClick()

        compose.waitForIdle()
        assertTrue("Keep deleted it anyway", fixture.isDirectory)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}

/** The listing is loaded off the main thread, so the row arrives after composition. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafely(
    text: String,
): Boolean = runCatching {
    onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
}.getOrDefault(false)
