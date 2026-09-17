package com.osamu.aide.ai.core

import java.net.URI

/**
 * Where the assistant sends its requests.
 *
 * The SDK's `baseUrl` takes anything that speaks Anthropic's `/v1/messages`
 * wire format, which is more than Anthropic: a self-hosted proxy, a gateway in
 * front of another provider, a company's own deployment. Supporting that is one
 * builder call -- what needs care is the string the user types, because every
 * way of getting it wrong fails at *request* time with an error that blames the
 * SDK.
 *
 * So this is a parser, not a text field. Each rejection below is a mistake a
 * user makes and an error message they would otherwise have to decode.
 */
sealed interface Endpoint {

    /** Anthropic's own API. What an empty field means, and the default. */
    data object Default : Endpoint

    /** A validated base URL, with no trailing slash and no `/v1`. */
    data class Custom(val baseUrl: String) : Endpoint

    /** Not usable, and why -- phrased for the person who typed it. */
    data class Rejected(val reason: String) : Endpoint
}

/**
 * Reads what the user typed into an [Endpoint].
 *
 * **`https` only, deliberately.** The API key travels as a request header, so
 * cleartext puts the user's billable credential on the wire in plaintext. It
 * would not work anyway -- `:app` ships no `network-security-config`, so
 * Android has blocked cleartext since API 28 -- but the platform's error
 * (`UnknownServiceException: CLEARTEXT communication to ... not permitted`)
 * arrives on the first chat message and names neither the setting nor the fix.
 * A local proxy over `http://localhost` would need a loopback exemption in the
 * shipped manifest, which is a security decision nobody has asked for yet.
 * **Measured, not assumed**: `LoopbackCleartextTest` gets
 * `Cleartext HTTP traffic to 127.0.0.1 not permitted` in this app's own
 * process. The phone's *browser* loads such a URL happily, which misleads
 * everyone who tries it by hand -- the policy is per app.
 *
 * So the local-model feature needs **both** halves changed together: a
 * `network-security-config` scoped to `127.0.0.1`, and an exception here. The
 * https rule above is right about a remote endpoint and wrong about loopback,
 * where there is no wire for the header to be read from.
 * `tools/localai/FINDINGS.md` §5.
 */
fun parseEndpoint(raw: String): Endpoint {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return Endpoint.Default

    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: return Endpoint.Rejected("That is not a URL.")

    when (uri.scheme?.lowercase()) {
        // No scheme at all: URI parses "api.example.com" as a bare path, so
        // this is the plain "pasted the hostname" case rather than a failure.
        null -> return Endpoint.Rejected("Needs a scheme — try https://$trimmed")
        "https" -> Unit
        // **http is allowed to loopback and nowhere else.** The rule above
        // exists because the API key travels as a request header, and on the
        // internet that is a credential in plaintext. On 127.0.0.1 there is no
        // wire: the peer is a `llama-server` this app started, and refusing it
        // was refusing the only address the local-model provider can use.
        //
        // The manifest has to agree -- `res/xml/network_security_config.xml`
        // permits cleartext for exactly these hosts -- and it is the platform
        // that enforces it, not this check. `LoopbackCleartextTest` measures
        // the refusal that appears when the two disagree.
        "http" -> if (!isLoopback(uri.host)) {
            return Endpoint.Rejected(
                "https is required for anything but 127.0.0.1. Your API key is " +
                    "sent as a request header, so http would put it on the " +
                    "network in plaintext.",
            )
        }
        else -> return Endpoint.Rejected("Only https endpoints are supported.")
    }

    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: return Endpoint.Rejected("No host in that URL.")

    // Dropping these silently would be worse than refusing them: a proxy whose
    // auth rides in the URL would appear to be configured and then 401.
    if (uri.userInfo != null) {
        return Endpoint.Rejected(
            "Credentials in the URL are not supported — the API key is sent " +
                "as a header. Enter the address on its own.",
        )
    }
    if (uri.query != null || uri.fragment != null) {
        return Endpoint.Rejected("Enter the base address only, with no ?query or #fragment.")
    }

    val port = if (uri.port != -1) ":${uri.port}" else ""
    // **The scheme the user gave, not a hardcoded one.** This read `https://`
    // unconditionally, which was harmless while `http` was rejected outright
    // and would silently rewrite a loopback address to a scheme the platform
    // then refuses -- a bug that would surface as a cleartext error for a URL
    // the user never typed.
    val scheme = uri.scheme.lowercase()
    return Endpoint.Custom("$scheme://$host$port${basePath(uri.path.orEmpty())}")
}

/**
 * The path the SDK should append `/v1/messages` to.
 *
 * Two corrections, both silent because both are unambiguous:
 *
 * A **trailing slash** would produce `//v1/messages`. `ScriptedApi` has trimmed
 * one off since the first test in this module, which is how obvious it is.
 *
 * A **trailing `/v1`** is the likelier mistake, because `/v1/messages` is what
 * every provider's documentation shows, so a user copies the URL down to the
 * version. The SDK adds `/v1` itself -- pinned by
 * `EndpointTest.the_sdk_appends_v1_messages_to_the_base_url` -- so a base
 * ending in `/v1` always yields `/v1/v1/messages` and a 404. It is safe to
 * strip rather than warn about: a proxy mounted at `/anything/v1/messages` has
 * base `/anything`, so no correct base URL for this SDK ends in `/v1`.
 */
private fun basePath(path: String): String =
    path.trimEnd('/').removeSuffix("/v1").trimEnd('/')

/**
 * Whether [host] is this device talking to itself.
 *
 * The three spellings a user or a server can produce. `URI.host` keeps the
 * brackets on an IPv6 literal, so `http://[::1]:8080` arrives here as `[::1]`
 * and the bare form is accepted too for anyone assembling the string by hand.
 *
 * Deliberately not "any address that resolves to a loopback": that would mean
 * a DNS lookup inside a parser, and a hostname that resolves to 127.0.0.1
 * today can resolve elsewhere tomorrow. The exemption in the manifest is by
 * literal name, so this matches by literal name.
 */
private fun isLoopback(host: String?): Boolean =
    host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")
