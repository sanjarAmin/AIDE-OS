package com.osamu.aide.ui.settings

import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.toolchain.manager.ToolchainComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every downloadable local model can actually be picked.
 *
 * **The bug this exists for.** The picker used to match a stored model name to
 * a component by finding the same size in both ids, on the reasoning that a new
 * model would need no edit here. That held until two models shared a size:
 * adding an abliterated 1.5B produced a second chip also reading "1.5B", and
 * the lookup returned the stock component for both -- so one of the two could
 * never be selected and nothing on screen said so. Nothing failed; a feature
 * was simply unreachable, which is the shape `ai/core/FINDINGS.md` keeps
 * warning about.
 */
class LocalModelPickerTest {

    @Test
    fun `every offered model maps to a distinct id`() {
        val ids = ToolchainComponent.LOCAL_MODELS.map(::modelIdFor)

        assertEquals("two components share a model id: $ids", ids.size, ids.distinct().size)
    }

    @Test
    fun `every component round-trips through its id`() {
        ToolchainComponent.LOCAL_MODELS.forEach { component ->
            val back = modelFor(modelIdFor(component))
            assertNotNull("${component.id} did not resolve back to a component", back)
            assertEquals(
                "${component.id} resolved to ${back?.id}",
                component.id,
                back?.id,
            )
        }
    }

    @Test
    fun `the provider offers exactly the models that can be downloaded`() {
        // A name in one list and not the other is a chip that selects nothing,
        // or a download with no way to choose it.
        assertEquals(
            ToolchainComponent.LOCAL_MODELS.map(::modelIdFor).toSet(),
            AiProviderType.LOCAL.availableModels.toSet(),
        )
    }

    @Test
    fun `the default model is one of them`() {
        assertNotNull(
            "the default names no downloadable component",
            modelFor(AiProviderType.LOCAL.defaultModel),
        )
    }

    @Test
    fun `an unknown or empty id selects nothing rather than guessing`() {
        // The old size match would happily return a 1.5B for "whatever-1.5b".
        assertEquals(null, modelFor(null))
        assertEquals(null, modelFor(""))
        assertEquals(null, modelFor("some-other-model-1.5b"))
    }

    @Test
    fun `only apache licensed models are offered`() {
        // The 3B of this family is under Qwen's research licence, which does
        // not permit commercial use; it is labelled rather than hidden, and the
        // label is what makes that honest. This asserts the labelling rule so a
        // model added later cannot arrive unmarked.
        val restricted = ToolchainComponent.LOCAL_MODELS.filter { "3b" in it.id }
        assertTrue(
            "a research-licence model is offered without saying so: " +
                restricted.map { it.displayName },
            restricted.all { it.displayName.contains("licence", ignoreCase = true) },
        )
    }
}
