package com.osamu.aide.engine.fast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one convention `:engine:fast` and `:toolchain:manager` share.
 *
 * The engine does not depend on the manager and should not -- compiling with
 * what it is given is its whole job. The cost of that is a directory name
 * agreed in two places, which is exactly the kind of agreement that rots
 * silently: rename the component and Kotlin stops being found, with no error
 * anywhere, just a project refused for a compiler that is sitting on disk.
 */
class KotlinToolchainProviderTest {

    @Test
    fun the_component_id_matches_the_one_the_manager_installs_under() {
        // Hard-coded rather than imported, for the same reason the two modules
        // are not coupled: this test is the seam, so it has to state both sides.
        assertEquals("kotlin-compiler", KotlinToolchainProvider.COMPONENT_ID)
    }
}
