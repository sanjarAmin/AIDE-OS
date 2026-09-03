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

    @Test
    fun gemini_default_model_is_gemini_3_7_flash() {
        assertEquals("gemini-3.7-flash", AiProviderType.GEMINI.defaultModel)
        assertTrue(AiProviderType.GEMINI.availableModels.contains("gemini-3.7-flash"))
        assertTrue(AiProviderType.GEMINI.availableModels.contains("gemini-3.5-flash-lite"))
        assertTrue(AiProviderType.GEMINI.availableModels.contains("gemini-2.5-pro"))
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
