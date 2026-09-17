package com.osamu.aide.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device provider's rules, which differ from every other provider's.
 *
 * [AiProviderType.LOCAL] is the first provider with **no credential at all**.
 * The peer is a `llama-server` this app started on its own loopback, so
 * readiness is "a process is listening" rather than "a key is stored" — and the
 * address is written by whatever started it, never typed.
 *
 * These are the invariants that make that safe. The one that matters most is
 * the last: a local provider must never fall back to a remote default, because
 * the failure mode is a request going somewhere the user did not choose.
 * `ApiKeyStore.isReady`'s own comment records that exact bug happening to
 * Custom, where a key with no address was sent to api.openai.com.
 */
class LocalProviderTest {

    @Test
    fun the_local_provider_advertises_no_key() {
        // Nothing in the enum suggests a credential, because there is none to
        // ask for and a key field would be a question with no right answer.
        assertEquals("local", AiProviderType.LOCAL.id)
        assertTrue(
            "the display name should not promise a service",
            AiProviderType.LOCAL.displayName.isNotBlank(),
        )
    }

    /**
     * Its models name the pinned downloads, not anything a service accepts.
     *
     * `llama-server` serves whatever it was launched with and ignores the
     * request's `model` field, so these exist to say which GGUF to start. They
     * have to stay recognisable against `ToolchainComponent.LOCAL_MODELS`,
     * which names the same four sizes.
     */
    @Test
    fun its_models_are_the_sizes_that_are_pinned_for_download() {
        val models = AiProviderType.LOCAL.availableModels

        assertEquals(4, models.size)
        for (size in listOf("0.5b", "1.5b", "3b", "7b")) {
            assertTrue("no model for $size in $models", models.any { it.contains(size) })
        }
    }

    /**
     * **The default is not the biggest.** Measured reasons, both recorded:
     * the 0.5B chose the wrong tool 0/5 times and answered about files it never
     * opened, and the 7B took 306 s to its first word on a flagship phone.
     * `tools/localai/FINDINGS.md` §4 and §8.
     */
    @Test
    fun the_default_model_is_the_smallest_one_that_behaves() {
        assertEquals("qwen2.5-coder-1.5b", AiProviderType.LOCAL.defaultModel)
        assertTrue(
            "the default must be one of the offered models",
            AiProviderType.LOCAL.defaultModel in AiProviderType.LOCAL.availableModels,
        )
    }

    /** It is reachable by id, like every other provider. */
    @Test
    fun it_resolves_from_its_stored_id() {
        assertEquals(AiProviderType.LOCAL, AiProviderType.fromId("local"))
        assertEquals(AiProviderType.LOCAL, AiProviderType.fromId("LOCAL"))
    }

    /**
     * A loopback address survives parsing with its scheme intact.
     *
     * The server speaks plain http and `parseEndpoint` used to rewrite every
     * accepted URL to `https://`, which would hand the client an address the
     * platform then refuses. `EndpointTest` covers the parser; this pins the
     * shape the local provider actually stores.
     */
    @Test
    fun the_address_a_running_server_would_publish_is_accepted_as_is() {
        val parsed = parseEndpoint("http://127.0.0.1:41234")

        assertTrue("a loopback address was rejected: $parsed", parsed is Endpoint.Custom)
        assertEquals("http://127.0.0.1:41234", (parsed as Endpoint.Custom).baseUrl)
    }

    /**
     * And a remote address still cannot arrive over plain http.
     *
     * The exemption is for loopback. If this ever passes, the
     * network-security-config and the parser have drifted apart and an API key
     * can leave the device in clear text.
     */
    @Test
    fun the_exemption_does_not_extend_beyond_loopback() {
        assertTrue(
            "http to a remote host was accepted",
            parseEndpoint("http://api.openai.com") is Endpoint.Rejected,
        )
    }

    /** With no server running there is no address, and therefore no session. */
    @Test
    fun an_absent_address_is_null_rather_than_a_remote_default() {
        // The parser is the only thing this test can reach without Android's
        // preferences; the point is that nothing here invents a fallback host.
        val parsed = parseEndpoint("")

        assertTrue("an empty address became a custom endpoint", parsed is Endpoint.Default)
        assertNull(
            "an empty address must not resolve to a remote base URL",
            (parsed as? Endpoint.Custom)?.baseUrl,
        )
    }
}
