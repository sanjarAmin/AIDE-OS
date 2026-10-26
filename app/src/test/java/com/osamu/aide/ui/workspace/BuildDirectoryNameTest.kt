package com.osamu.aide.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a project builds, which ProjectBuilder and LanguageServices must agree
 * on and no two projects may share.
 *
 * Every cloned project is rooted at `<clone>/app`, and the directory name alone
 * put all of them in one build directory: each build picked up the previous
 * clone's incremental state, and the editor resolved `R` against whichever app
 * had built last.
 */
class BuildDirectoryNameTest {

    @Test
    fun two_clones_rooted_at_app_build_apart() {
        val first = buildDirectoryName(File("/workspace/upstream/app"))
        val second = buildDirectoryName(File("/workspace/other/app"))

        assertNotEquals(first, second)
    }

    @Test
    fun the_same_project_builds_in_the_same_place_every_time() {
        assertEquals(
            buildDirectoryName(File("/workspace/Demo")),
            buildDirectoryName(File("/workspace/Demo")),
        )
    }

    /** Kept readable: whoever opens the cache should still see which project it is. */
    @Test
    fun the_name_still_leads() {
        assertTrue(buildDirectoryName(File("/workspace/Demo")).startsWith("Demo-"))
    }
}
