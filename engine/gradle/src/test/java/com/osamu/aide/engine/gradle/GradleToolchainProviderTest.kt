package com.osamu.aide.engine.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conventions `:engine:gradle` and `:toolchain:manager` share.
 *
 * The engine does not depend on the manager -- building with what it is given
 * is its whole job -- and the cost of that is directory names agreed in two
 * places. That kind of agreement rots silently: rename a component and Gradle
 * stops being found, with no error anywhere, just a project refused for a
 * runtime sitting on disk.
 */
class GradleToolchainProviderTest {

    @Test
    fun the_component_ids_match_the_ones_the_manager_installs_under() {
        // Stated rather than imported, because this test is the seam and has to
        // state both sides for the comparison to mean anything.
        assertEquals("openjdk-21", GradleToolchainProvider.JDK_COMPONENT_ID)
        assertEquals("gradle", GradleToolchainProvider.GRADLE_COMPONENT_ID)
    }

    /**
     * The directories, not just the ids: the manager turns `;` into `-`. The
     * ids above always matched, and a downloaded SDK was still never found.
     */
    @Test
    fun the_sdk_is_looked_for_where_the_manager_puts_it() {
        assertEquals(
            "platforms-android-36",
            GradleToolchainProvider.directoryOf(GradleToolchainProvider.PLATFORM_COMPONENT_ID),
        )
        assertEquals(
            "build-tools-36.0.0",
            GradleToolchainProvider.directoryOf(GradleToolchainProvider.BUILD_TOOLS_COMPONENT_ID),
        )
    }
}
