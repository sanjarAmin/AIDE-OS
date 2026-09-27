package com.osamu.aide.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {

    @Test
    fun default_provider_is_gemini() {
        assertEquals(AiProviderType.GEMINI, AiProviderType.DEFAULT)
        assertEquals("gemini", AiProviderType.DEFAULT.id)
        assertEquals("Google Gemini", AiProviderType.DEFAULT.displayName)
    }

    /**
     * The current defaults, spelled out.
     *
     * This test has to be edited when a provider retires a generation, and that
     * is the point: the IDs here went stale once already, and nothing failed
     * until a request came back 404. A test that has to be updated deliberately
     * is the cheapest alarm available.
     */
    @Test
    fun each_provider_defaults_to_a_current_model() {
        assertEquals("gemini-3.8-flash", AiProviderType.GEMINI.defaultModel)
        assertEquals("gpt-5.6", AiProviderType.OPENAI.defaultModel)
        assertEquals("claude-opus-5", AiProviderType.ANTHROPIC.defaultModel)
    }

    /**
     * No retired ID survives anywhere in the menu.
     *
     * The menu is what the user picks from, so a dead ID in it is a 404 they
     * chose. These are the ones this list actually shipped with.
     */
    @Test
    fun no_retired_model_is_still_offered() {
        val retired = listOf(
            "gpt-4o", "gpt-4o-mini", "o3-mini", "o1",
            "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-latest", "claude-3-5-haiku-latest",
            "gemini-2.0-flash", "gemini-2.0-flash-lite",
        )
        for (provider in AiProviderType.entries) {
            for (model in provider.availableModels) {
                assertTrue(
                    "$provider still offers the retired model $model",
                    model !in retired,
                )
            }
        }
    }

    /**
     * A client's default is the enum's default.
     *
     * Each client used to carry its own literal, and that is exactly how the
     * two drifted: the enum offered one model and the client requested another,
     * with nothing to say so. Tying them is the fix; this is what keeps them
     * tied.
     */
    @Test
    fun a_client_defaults_to_the_model_its_provider_advertises() {
        assertEquals(AiProviderType.GEMINI.defaultModel, GeminiAiClient().model)
        assertEquals(AiProviderType.OPENAI.defaultModel, OpenAiClient().model)
    }

    @Test
    fun provider_from_id_resolves_correctly() {
        assertEquals(AiProviderType.GEMINI, AiProviderType.fromId("gemini"))
        assertEquals(AiProviderType.OPENAI, AiProviderType.fromId("openai"))
        assertEquals(AiProviderType.ANTHROPIC, AiProviderType.fromId("anthropic"))
        assertEquals(AiProviderType.CUSTOM, AiProviderType.fromId("custom"))
        // Unknown falls back to default (Gemini)
        assertEquals(AiProviderType.GEMINI, AiProviderType.fromId("unknown-provider"))
    }

    @Test
    fun all_providers_have_non_empty_models() {
        for (provider in AiProviderType.entries) {
            assertTrue(provider.availableModels.isNotEmpty())
            assertTrue(provider.defaultModel.isNotBlank())
            assertTrue(provider.defaultModel in provider.availableModels)
        }
    }
}
