package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The endpoint the assistant talks to.
 *
 * Two halves. The first is [parseEndpoint], which is pure and would be a JVM
 * test in a module that had a JVM source set; it lives here for the same reason
 * `cleanCompletion`'s tests do -- this module is instrumented throughout.
 *
 * The second half is the one that could not be a unit test at all: that the SDK
 * appends `/v1/messages` to whatever base URL it is given. Every rejection and
 * every silent correction below is justified by that behaviour, so it is
 * asserted against the real client rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class EndpointTest {

    private var api: ScriptedApi? = null

    @After
    fun tearDown() {
        api?.stop()
    }

    // -- what the field accepts ---------------------------------------------

    @Test
    fun an_empty_field_means_anthropic() {
        assertEquals(Endpoint.Default, parseEndpoint(""))
        assertEquals(Endpoint.Default, parseEndpoint("   \n "))
    }

    @Test
    fun a_plain_https_url_is_taken_as_written() {
        assertEquals(
            Endpoint.Custom("https://api.example.com"),
            parseEndpoint("https://api.example.com"),
        )
    }

    @Test
    fun a_port_and_a_path_survive() {
        assertEquals(
            Endpoint.Custom("https://gateway.internal:8443/anthropic"),
            parseEndpoint("https://gateway.internal:8443/anthropic"),
        )
    }

    @Test
    fun surrounding_whitespace_is_trimmed() {
        assertEquals(
            Endpoint.Custom("https://api.example.com"),
            parseEndpoint("  https://api.example.com\n"),
        )
    }

    // -- the two silent corrections -----------------------------------------

    /** A trailing slash would make the SDK request `//v1/messages`. */
    @Test
    fun a_trailing_slash_is_removed() {
        assertEquals(
            Endpoint.Custom("https://api.example.com"),
            parseEndpoint("https://api.example.com/"),
        )
    }

    /**
     * The likeliest mistake, because `/v1/messages` is what the docs show.
     *
     * Pinned together with [the_sdk_appends_v1_messages_to_the_base_url], which
     * is what makes stripping this correct rather than a guess.
     */
    @Test
    fun a_trailing_v1_is_removed_because_the_sdk_adds_its_own() {
        assertEquals(
            Endpoint.Custom("https://api.example.com"),
            parseEndpoint("https://api.example.com/v1"),
        )
        assertEquals(
            Endpoint.Custom("https://gateway.internal/anthropic"),
            parseEndpoint("https://gateway.internal/anthropic/v1/"),
        )
    }

    /** Only a whole path segment. A host or a path that merely starts "v1" stays. */
    @Test
    fun something_that_only_looks_like_a_version_is_left_alone() {
        assertEquals(
            Endpoint.Custom("https://v1.example.com"),
            parseEndpoint("https://v1.example.com"),
        )
        assertEquals(
            Endpoint.Custom("https://api.example.com/v1beta"),
            parseEndpoint("https://api.example.com/v1beta"),
        )
    }

    // -- what it refuses ----------------------------------------------------

    /**
     * The rejection that is about the key rather than about URLs.
     *
     * Cleartext would put a billable credential on the wire in plaintext, and
     * `:app` ships no `network-security-config`, so it would fail anyway --
     * with a platform error naming neither the setting nor the fix.
     */
    @Test
    fun http_is_refused_and_the_reason_names_the_key() {
        val rejected = parseEndpoint("http://api.example.com") as Endpoint.Rejected

        assertTrue(rejected.reason, "https" in rejected.reason)
        assertTrue(
            "the reason must say why, not just that it is refused: ${rejected.reason}",
            "plaintext" in rejected.reason,
        )
    }

    /** A pasted hostname is common enough to deserve the fix in the message. */
    @Test
    fun a_bare_hostname_is_refused_with_the_corrected_url() {
        val rejected = parseEndpoint("api.example.com") as Endpoint.Rejected

        assertTrue(rejected.reason, "https://api.example.com" in rejected.reason)
    }

    @Test
    fun other_schemes_are_refused() {
        assertTrue(parseEndpoint("ftp://api.example.com") is Endpoint.Rejected)
        assertTrue(parseEndpoint("file:///etc/passwd") is Endpoint.Rejected)
    }

    /**
     * Refused rather than dropped.
     *
     * Silently discarding them would leave a proxy that authenticates by URL
     * looking configured and then failing with a 401 nobody can explain.
     */
    @Test
    fun credentials_in_the_url_are_refused_rather_than_stripped() {
        val rejected = parseEndpoint("https://user:pw@api.example.com") as Endpoint.Rejected

        assertTrue(rejected.reason, "Credentials" in rejected.reason)
    }

    @Test
    fun a_query_or_fragment_is_refused() {
        assertTrue(parseEndpoint("https://api.example.com/?key=abc") is Endpoint.Rejected)
        assertTrue(parseEndpoint("https://api.example.com/#top") is Endpoint.Rejected)
    }

    @Test
    fun nonsense_is_refused_rather_than_thrown() {
        assertTrue(parseEndpoint("https://") is Endpoint.Rejected)
        assertTrue(parseEndpoint("h t t p s://x") is Endpoint.Rejected)
    }

    // -- the SDK's half ------------------------------------------------------

    /**
     * The assumption everything above rests on, asserted rather than believed.
     *
     * If a future SDK stopped adding `/v1` -- or started wanting it in the base
     * URL -- every custom endpoint would 404 and `basePath` would be actively
     * making it worse. This is the test that would say so.
     *
     * Cleartext, which [parseEndpoint] refuses: `androidTest` has a loopback
     * exemption in its manifest, and this is exercising the SDK's URL handling
     * rather than the field's validation.
     */
    @Test
    fun the_sdk_appends_v1_messages_to_the_base_url() {
        val scripted = ScriptedApi(listOf(ScriptedApi.text("hi"))).also { api = it }

        Assistant.defaultClient("test-key", scripted.baseUrl)
            .messages()
            .create(
                MessageCreateParams.builder()
                    .model(Model.of("claude-opus-5"))
                    .maxTokens(16)
                    .addUserMessage("hello")
                    .build(),
            )

        assertEquals(1, scripted.requestCount)
        assertEquals("/v1/messages", scripted.path(0))
    }

    /**
     * A base URL with a path prefix keeps it, and `/v1/messages` lands under it.
     *
     * This is the shape a self-hosted proxy takes, and getting it wrong would
     * strip the mount point and hit the wrong host root.
     */
    @Test
    fun a_path_prefix_is_preserved_under_the_appended_route() {
        val scripted = ScriptedApi(listOf(ScriptedApi.text("hi"))).also { api = it }

        Assistant.defaultClient("test-key", "${scripted.baseUrl}/proxy")
            .messages()
            .create(
                MessageCreateParams.builder()
                    .model(Model.of("claude-opus-5"))
                    .maxTokens(16)
                    .addUserMessage("hello")
                    .build(),
            )

        assertEquals("/proxy/v1/messages", scripted.path(0))
    }

    // ---- http, for loopback only ----

    /**
     * **The address the local-model provider has to use.**
     *
     * `http` was rejected outright, which was right for every endpoint that
     * existed at the time and wrong for the one that came later: a
     * `llama-server` this app starts listens on 127.0.0.1, and there is no
     * wire for the API-key header to be read from. The manifest's
     * `network_security_config.xml` permits cleartext for exactly these hosts,
     * and `LoopbackCleartextTest` measures what happens when it does not.
     */
    @Test
    fun http_is_accepted_for_loopback() {
        for (address in listOf(
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://127.0.0.1",
        )) {
            val parsed = parseEndpoint(address)
            assertTrue("$address was rejected: $parsed", parsed is Endpoint.Custom)
        }
    }

    /**
     * And the scheme survives.
     *
     * `parseEndpoint` rebuilt its answer with a hardcoded `https://`, which
     * was invisible while http could never get that far. Left alone it would
     * hand back an `https` URL for a loopback server that speaks plain http,
     * and the failure would be a cleartext error naming an address the user
     * never typed.
     */
    @Test
    fun a_loopback_address_keeps_its_http_scheme() {
        val parsed = parseEndpoint("http://127.0.0.1:8080") as Endpoint.Custom

        assertEquals("http://127.0.0.1:8080", parsed.baseUrl)
    }

    /** http to anywhere else is still refused, and the reason still says why. */
    @Test
    fun http_is_still_refused_for_a_remote_host() {
        val parsed = parseEndpoint("http://api.example.com")

        assertTrue("http to a remote host was accepted: $parsed", parsed is Endpoint.Rejected)
        assertTrue(
            "the reason no longer explains the key: ${(parsed as Endpoint.Rejected).reason}",
            parsed.reason.contains("header"),
        )
    }

    /**
     * A hostname that merely looks local is not loopback.
     *
     * The match is by literal name, matching the manifest exemption. Resolving
     * names here would put a DNS lookup in a parser, and a host that resolves
     * to 127.0.0.1 today can resolve elsewhere tomorrow.
     */
    @Test
    fun a_host_that_only_looks_local_is_not_exempt() {
        for (address in listOf(
            "http://localhost.example.com",
            "http://127.0.0.1.example.com",
            "http://mylocalhost",
        )) {
            assertTrue(
                "$address was treated as loopback",
                parseEndpoint(address) is Endpoint.Rejected,
            )
        }
    }

    /** https to loopback is fine too; nothing about this narrows it. */
    @Test
    fun https_to_loopback_is_unaffected() {
        val parsed = parseEndpoint("https://127.0.0.1:8443") as Endpoint.Custom

        assertEquals("https://127.0.0.1:8443", parsed.baseUrl)
    }
}
