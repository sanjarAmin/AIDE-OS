package com.osamu.aide.engine.fast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The convention `:engine:fast` and `:toolchain:manager` share for the C/C++
 * toolchain, for the reason [KotlinToolchainProviderTest] gives.
 *
 * It rots the same way and worse: the toolchain is 551 MB, so a rename would
 * have the engine refuse a native project as "toolchain not installed" while
 * half a gigabyte of clang sits on disk under the old name, and the obvious fix
 * -- download it again -- would change nothing.
 */
class NativeToolchainProviderTest {

    /**
     * Stated on both sides rather than imported, because this test is the seam.
     * The manager builds its id as `clang-21.1.8-<abi>` in
     * `ToolchainComponent.nativeToolchain`.
     */
    @Test
    fun the_component_prefix_matches_the_one_the_manager_installs_under() {
        assertEquals("clang-21.1.8", NativeToolchainProvider.COMPONENT_PREFIX)
    }

    /**
     * The ABI is part of the id, so the two architectures never collide -- and
     * so a device cannot pick up a toolchain built for the other one, which
     * would fail as an exec error rather than as anything legible.
     */
    @Test
    fun the_abi_is_part_of_the_component_id() {
        assertEquals("clang-21.1.8-arm64-v8a", "${NativeToolchainProvider.COMPONENT_PREFIX}-arm64-v8a")
        assertEquals("clang-21.1.8-x86_64", "${NativeToolchainProvider.COMPONENT_PREFIX}-x86_64")
    }
}
