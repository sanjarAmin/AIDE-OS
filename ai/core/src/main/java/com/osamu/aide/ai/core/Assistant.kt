package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.osamu.aide.core.common.DispatcherProvider
import java.io.File

/**
 * Builds a session for one project, for the active AI model provider.
 *
 * Supports Gemini as the default provider with Google Sign-In or API Key,
 * as well as OpenAI, Anthropic, and Custom/Compatible endpoints.
 */
class Assistant(
    private val keys: ApiKeyStore,
    private val dispatchers: DispatcherProvider,
    private val clientFactory: (String, String?) -> AnthropicClient = ::defaultClient,
) {

    /**
     * Null when the user has not supplied credentials for the active provider.
     */
    fun session(
        projectDir: File,
        approver: Approver,
        extraTools: List<AideTool> = emptyList(),
    ): AiSession? {
        val toolset = ProjectToolset(ProjectFiles(projectDir), extraTools)
        val provider = keys.activeProvider()

        return when (provider) {
            AiProviderType.GEMINI -> {
                val apiKey = keys.geminiApiKey()
                val oauthToken = keys.googleAccessToken()
                if (apiKey.isNullOrBlank() && oauthToken.isNullOrBlank()) return null
                val client = GeminiAiClient(
                    apiKey = apiKey,
                    oauthToken = oauthToken,
                    model = keys.activeModel(AiProviderType.GEMINI),
                )
                AiSession(client, toolset, approver, dispatchers)
            }
            AiProviderType.OPENAI -> {
                val apiKey = keys.openAiApiKey() ?: return null
                val client = OpenAiClient(
                    apiKey = apiKey,
                    customBaseUrl = keys.openAiBaseUrl(),
                    model = keys.activeModel(AiProviderType.OPENAI),
                    provider = AiProviderType.OPENAI,
                )
                AiSession(client, toolset, approver, dispatchers)
            }
            AiProviderType.CUSTOM -> {
                val client = OpenAiClient(
                    apiKey = keys.customApiKey(),
                    customBaseUrl = keys.customBaseUrl(),
                    model = keys.activeModel(AiProviderType.CUSTOM),
                    provider = AiProviderType.CUSTOM,
                )
                AiSession(client, toolset, approver, dispatchers)
            }
            AiProviderType.ANTHROPIC -> {
                val key = keys.read() ?: return null
                AiSession(
                    client = clientFactory(key, keys.baseUrl()),
                    assembler = PromptAssembler(toolset),
                    toolset = toolset,
                    approver = approver,
                    dispatchers = dispatchers,
                )
            }
        }
    }

    /**
     * A completer for the active provider, if credentials exist.
     */
    fun completer(): InlineCompleter? {
        val provider = keys.activeProvider()
        return when (provider) {
            AiProviderType.GEMINI -> {
                val apiKey = keys.geminiApiKey()
                val oauthToken = keys.googleAccessToken()
                if (apiKey.isNullOrBlank() && oauthToken.isNullOrBlank()) return null
                val client = GeminiAiClient(
                    apiKey = apiKey,
                    oauthToken = oauthToken,
                    model = keys.activeModel(AiProviderType.GEMINI),
                )
                InlineCompleter(client, dispatchers)
            }
            AiProviderType.OPENAI -> {
                val apiKey = keys.openAiApiKey() ?: return null
                val client = OpenAiClient(
                    apiKey = apiKey,
                    customBaseUrl = keys.openAiBaseUrl(),
                    model = keys.activeModel(AiProviderType.OPENAI),
                    provider = AiProviderType.OPENAI,
                )
                InlineCompleter(client, dispatchers)
            }
            AiProviderType.CUSTOM -> {
                val client = OpenAiClient(
                    apiKey = keys.customApiKey(),
                    customBaseUrl = keys.customBaseUrl(),
                    model = keys.activeModel(AiProviderType.CUSTOM),
                    provider = AiProviderType.CUSTOM,
                )
                InlineCompleter(client, dispatchers)
            }
            AiProviderType.ANTHROPIC -> {
                val key = keys.read() ?: return null
                InlineCompleter(clientFactory(key, keys.baseUrl()), dispatchers)
            }
        }
    }

    internal companion object {
        fun defaultClient(apiKey: String, baseUrl: String?): AnthropicClient {
            var builder = AnthropicOkHttpClient.builder().apiKey(apiKey)
            if (baseUrl != null) builder = builder.baseUrl(baseUrl)
            return builder.build()
        }
    }
}

/**
 * The project, as the context block of the prompt sees it.
 */
fun projectContext(files: ProjectFiles): String =
    when (val listing = files.list()) {
        is ProjectFiles.Outcome.Ok -> listing.content
        is ProjectFiles.Outcome.Refused -> "The project could not be listed: ${listing.reason}"
    }
