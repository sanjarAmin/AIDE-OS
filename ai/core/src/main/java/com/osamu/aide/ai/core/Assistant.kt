package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.osamu.aide.core.common.DispatcherProvider
import java.io.File

/**
 * Builds a session for one project, if there is a key to build it with.
 *
 * The two things this exists to keep out of the UI are the client's lifetime
 * and the project context string. Neither is complicated; both are easy to get
 * subtly wrong in a composable.
 */
class Assistant(
    private val keys: ApiKeyStore,
    private val dispatchers: DispatcherProvider,
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient,
) {

    /**
     * Null when the user has not supplied a key. That is the normal state.
     *
     * [extraTools] is fixed for the life of the session on purpose: tool
     * definitions sit at the very front of the cached prefix, so a list that
     * varies between turns costs the cache on every one of them. Building it
     * here, once, is what makes that hard to get wrong.
     */
    fun session(
        projectDir: File,
        approver: Approver,
        extraTools: List<AideTool> = emptyList(),
    ): AiSession? {
        val key = keys.read() ?: return null
        val toolset = ProjectToolset(ProjectFiles(projectDir), extraTools)

        return AiSession(
            client = clientFactory(key),
            assembler = PromptAssembler(toolset),
            toolset = toolset,
            approver = approver,
            dispatchers = dispatchers,
        )
    }

    /**
     * A completer, if there is a key. Cheap to build -- it holds no state.
     *
     * Its own client for the same reason [session] gets one: the key can change
     * while the app is running, and a cached client keeps using the old one.
     */
    fun completer(): InlineCompleter? {
        val key = keys.read() ?: return null
        return InlineCompleter(clientFactory(key), dispatchers)
    }

    private companion object {
        /**
         * One client per session rather than a shared singleton.
         *
         * The client holds the API key, and the key can change while the app is
         * running -- the settings screen exists for that. A cached client would
         * keep authenticating with the old one until the process restarted, and
         * the symptom is a 401 the user cannot explain after they just fixed
         * their key.
         */
        fun defaultClient(apiKey: String): AnthropicClient =
            AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    }
}

/**
 * The project, as the cached half of the prompt sees it.
 *
 * Derived from the file tree and nothing else. **Not** the open file, the
 * cursor, or the time: this string lands above the cache breakpoint, so
 * anything that changes per turn invalidates the whole prefix and costs the
 * cache for a line nobody reads. Per-turn detail belongs in the user's message.
 * `PromptAssembler`'s docs have the reasoning.
 */
fun projectContext(files: ProjectFiles): String =
    when (val listing = files.list()) {
        is ProjectFiles.Outcome.Ok -> listing.content
        is ProjectFiles.Outcome.Refused -> "The project could not be listed: ${listing.reason}"
    }
