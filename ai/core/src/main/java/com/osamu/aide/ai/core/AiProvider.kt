package com.osamu.aide.ai.core

/**
 * Supported AI model providers in AIDE-OS.
 *
 * [GEMINI] is the flagship default provider, matching Android Studio's Gemini
 * integration with Google Sign-In and Gemini API keys.
 */
enum class AiProviderType(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val availableModels: List<String>,
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        defaultModel = "gemini-3.7-flash",
        availableModels = listOf(
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-2.5-pro",
        ),
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultModel = "gpt-4o",
        availableModels = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "o3-mini",
            "o1",
        ),
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        defaultModel = "claude-3-7-sonnet-20250219",
        availableModels = listOf(
            "claude-3-7-sonnet-20250219",
            "claude-3-5-sonnet-latest",
            "claude-3-5-haiku-latest",
            "claude-opus-5",
        ),
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom / Compatible",
        defaultModel = "default",
        availableModels = listOf(
            "default",
            "deepseek-chat",
            "llama3.3:70b",
            "qwen2.5-coder",
        ),
    );

    companion object {
        val DEFAULT = GEMINI

        fun fromId(id: String?): AiProviderType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
