package com.osamu.aide.ui.settings

import com.osamu.aide.ai.core.AiProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyClipboardTest {

    @Test
    fun gemini_key_pattern_matches_valid_shapes() {
        val validKey = "AIzaSyD-sampleGeminiApiKey1234567890ABC"
        assertTrue(looksLikeKeyFor(AiProviderType.GEMINI, validKey))
        assertTrue(looksLikeKeyFor(AiProviderType.GEMINI, "  $validKey  "))
    }

    @Test
    fun gemini_key_pattern_rejects_invalid_keys() {
        assertFalse(looksLikeKeyFor(AiProviderType.GEMINI, "AIzaShort"))
        assertFalse(looksLikeKeyFor(AiProviderType.GEMINI, "sk-ant-api03-1234567890123456789012345678901234567890"))
        assertFalse(looksLikeKeyFor(AiProviderType.GEMINI, "AIzaSyWith SpaceInMiddle1234567890123456"))
        assertFalse(looksLikeKeyFor(AiProviderType.GEMINI, "AIzaSyWith\nNewlineInMiddle123456789012"))
        assertFalse(looksLikeKeyFor(AiProviderType.GEMINI, ""))
    }

    @Test
    fun anthropic_key_pattern_matches_and_rejects() {
        val valid = "sk-ant-api03-sampleAnthropicKey1234567890123456789012345"
        assertTrue(looksLikeKeyFor(AiProviderType.ANTHROPIC, valid))
        assertFalse(looksLikeKeyFor(AiProviderType.ANTHROPIC, "AIzaSyD-sampleGeminiApiKey1234567890ABC"))
        assertFalse(looksLikeKeyFor(AiProviderType.ANTHROPIC, "sk-ant-short"))
    }

    @Test
    fun openai_key_pattern_matches_and_rejects() {
        val valid = "sk-proj-sampleOpenAiKey12345678901234567890123456789012"
        assertTrue(looksLikeKeyFor(AiProviderType.OPENAI, valid))
        // Anthropic key starts with sk-ant-, which must not match OpenAI
        assertFalse(looksLikeKeyFor(AiProviderType.OPENAI, "sk-ant-api03-sampleAnthropicKey1234567890123456789012345"))
    }

    @Test
    fun custom_provider_matches_no_hardcoded_pattern() {
        assertFalse(looksLikeKeyFor(AiProviderType.CUSTOM, "AIzaSyD-sampleGeminiApiKey1234567890ABC"))
        assertFalse(looksLikeKeyFor(AiProviderType.CUSTOM, "sk-sampleKey12345678901234567890123456789012"))
    }

    @Test
    fun console_urls_are_mapped_correctly() {
        assertEquals("https://aistudio.google.com/app/apikey", providerConsoleUrl(AiProviderType.GEMINI))
        assertEquals("https://console.anthropic.com/settings/keys", providerConsoleUrl(AiProviderType.ANTHROPIC))
        assertEquals("https://platform.openai.com/api-keys", providerConsoleUrl(AiProviderType.OPENAI))
        assertNull(providerConsoleUrl(AiProviderType.CUSTOM))
    }

    @Test
    fun console_labels_are_meaningful() {
        assertTrue(providerConsoleLabel(AiProviderType.GEMINI).contains("Google AI Studio"))
        assertTrue(providerConsoleLabel(AiProviderType.ANTHROPIC).contains("Anthropic"))
        assertTrue(providerConsoleLabel(AiProviderType.OPENAI).contains("OpenAI"))
        assertEquals("Get key from provider", providerConsoleLabel(AiProviderType.CUSTOM))
    }

    @Test
    fun whitespace_and_tab_handling_in_key_patterns() {
        val validGemini = "\t AIzaSyD-sampleGeminiApiKey1234567890ABC \n"
        assertTrue(looksLikeKeyFor(AiProviderType.GEMINI, validGemini))
        val withInternalTab = "AIzaSyD-sample\tGeminiApiKey1234567890ABC"
        assertFalse(looksLikeKeyFor(AiProviderType.GEMINI, withInternalTab))
    }
}
