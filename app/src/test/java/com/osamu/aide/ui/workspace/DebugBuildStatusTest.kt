package com.osamu.aide.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Debug tab says while the build a Debug tap started is its whole
 * story.
 *
 * A Kotlin debug build that died left the tab reading "Not debugging" beside a
 * Debug button -- the tap looked ignored, and the reason was in the Build tab.
 */
class DebugBuildStatusTest {

    @Test
    fun an_ordinary_build_is_none_of_the_debug_tabs_business() {
        assertNull(debugBuildStatus(BuildUiState(isRunning = true)))
        assertNull(debugBuildStatus(BuildUiState(outcome = "Compile failed.")))
    }

    @Test
    fun a_running_debug_build_says_so() {
        assertEquals(BUILDING_TO_DEBUG, debugBuildStatus(BuildUiState(isRunning = true, isDebug = true)))
    }

    @Test
    fun a_failed_debug_build_carries_its_reason() {
        val status = debugBuildStatus(
            BuildUiState(isDebug = true, outcome = "The build stopped with an error. SecurityException"),
        )
        assertEquals(
            "The build failed, so there is nothing to debug. The build stopped with an error. SecurityException",
            status,
        )
    }

    @Test
    fun a_successful_debug_build_hands_over_to_the_session() {
        assertNull(debugBuildStatus(BuildUiState(isDebug = true, succeeded = true, outcome = "Built in 900 ms.")))
    }
}
