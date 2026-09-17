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
            // `gemini-2.5-pro` was here and is gone: the models endpoint still
            // lists it, and calling it returns 404 "no longer available to new
            // users". Listing a model is not permission to call it -- see
            // FINDINGS.md section 15.
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
    /**
     * A `llama-server` this app started, on 127.0.0.1.
     *
     * **The models are the pinned GGUFs, not names a service accepts.**
     * llama-server serves whatever model it was launched with and ignores the
     * `model` field, so these exist to say *which download to start* and to
     * give the picker something meaningful -- not to be sent anywhere. They
     * must stay in step with `ToolchainComponent.LOCAL_MODELS`.
     *
     * The default is the 1.5B and deliberately not the largest available.
     * `tools/localai/FINDINGS.md` §4 measured the 0.5B choosing the wrong tool
     * 0/5 times and answering about files it never read, and §8 measured the 7B
     * at 306 s to first token on a flagship phone. The 1.5B is the smallest one
     * that behaves and the largest one that answers quickly.
     */
    LOCAL(
        id = "local",
        // One word, for the reason CUSTOM's name is one word.
        displayName = "On device",
        defaultModel = "qwen2.5-coder-1.5b",
        availableModels = listOf(
            "qwen2.5-coder-1.5b",
            "qwen2.5-coder-3b",
            "qwen2.5-coder-0.5b",
            "qwen2.5-coder-7b",
        ),
    ),
    CUSTOM(
        id = "custom",
        // One word. "Custom / Compatible" is four times the width of the
        // longest other name, and in a row of chips on a phone it wrapped
        // character by character into a column six lines tall.
        displayName = "Custom",
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
