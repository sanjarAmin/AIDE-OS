package com.osamu.aide.ai.core

/**
 * Supported AI model providers in AIDE-OS.
 *
 * [GEMINI] is the flagship default provider, matching Android Studio's Gemini
 * integration with Google Sign-In and Gemini API keys.
 *
 * **This is the only place a model ID is written.** Each client used to carry
 * its own default as a literal as well, and the two drifted: the enum offered
 * models the client would never pick, and both lists outlived the models
 * themselves. A retired ID does not fail at build time, or at startup, or when
 * the user picks it from the menu — it fails on the first request, as a 404
 * from the provider, which reads to the user as "the assistant is broken".
 *
 * These expire. When a provider retires a generation, this list is what needs
 * editing; nothing else should need touching.
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
        defaultModel = "gemini-3.8-flash",
        availableModels = listOf(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-2.5-pro",
        ),
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        // `gpt-5.6` is the alias for `gpt-5.6-sol`, the flagship; terra and luna
        // are the cheaper tiers of the same generation. The GPT-4o and o-series
        // IDs this list used to carry are retired.
        defaultModel = "gpt-5.6",
        availableModels = listOf(
            "gpt-5.6",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
        ),
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        defaultModel = "claude-opus-5",
        availableModels = listOf(
            "claude-opus-5",
            "claude-sonnet-5",
            "claude-haiku-4-5",
            "claude-fable-5-1",
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
