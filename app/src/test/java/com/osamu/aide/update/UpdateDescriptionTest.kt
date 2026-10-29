package com.osamu.aide.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences Home's banner and the About page share. */
class UpdateDescriptionTest {

    private val release = AppRelease(
        tag = "app-preview-147a159",
        title = "AIDE-OS preview (147a159): Home and Settings, redrawn",
        commit = "147a159",
        pageUrl = "",
        apkUrl = "",
        apkBytes = 56_260_128,
        apkSha256 = null,
    )

    @Test
    fun an_update_is_described_by_its_headline_and_distance() {
        assertEquals(
            "Home and Settings, redrawn · 2 commits newer than this build.",
            UpdateState.Available(release, commitsAhead = 2).describe(),
        )
        assertEquals("1 commit", commitsLabel(1))
    }

    @Test
    fun a_title_with_no_headline_is_shown_whole() {
        assertEquals("AIDE-OS preview", release.copy(title = "AIDE-OS preview").headline)
    }

    @Test
    fun progress_is_in_whole_megabytes() {
        assertEquals(
            "Downloading 23 MB of 53 MB.",
            UpdateState.Downloading(release, bytes = 24_117_248, total = 56_260_128).describe(),
        )
    }

    @Test
    fun installing_warns_that_the_app_will_close() {
        // Said before the dialog, because afterwards this process is gone.
        assertTrue(UpdateState.Installing(release).describe().contains("closes"))
    }
}
