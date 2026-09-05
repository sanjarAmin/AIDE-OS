package com.osamu.aide.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GoogleAuthManagerTest {

    @Test
    fun code_verifier_satisfies_rfc_7636() {
        val verifier = GoogleAuthManager.generateCodeVerifier()
        assertTrue("Verifier length was ${verifier.length}", verifier.length in 43..128)
        val validChars = Regex("^[a-zA-Z0-9-._~]+$")
        assertTrue("Verifier contains invalid characters", validChars.matches(verifier))
    }

    @Test
    fun code_challenge_is_base64_url_encoded_sha256() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = GoogleAuthManager.generateCodeChallenge(verifier)
        // Known RFC 7636 Appendix B test vector:
        // verifier: dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
        // challenge: E9Melhoa2OwvFrGMTJguCH5rtG6Zv5iNIfAhAhrzbQg
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun auth_url_contains_required_parameters() {
        val manager = GoogleAuthManager(
            keyStore = null,
            clientId = "test-client-id",
            redirectUri = "com.osamu.aide://oauth2callback",
        )
        val state = manager.createAuthorizationRequest()

        assertNotNull(state.authUrl)
        assertTrue(state.authUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(state.authUrl.contains("client_id=test-client-id"))
        assertTrue(state.authUrl.contains("response_type=code"))
        assertTrue(state.authUrl.contains("redirect_uri=com.osamu.aide%3A%2F%2Foauth2callback"))
        assertTrue(state.authUrl.contains("scope="))
    }

    @Test
    fun jwt_profile_parsing_extracts_email() {
        // A dummy JWT with payload {"email":"developer@example.com","name":"Android Dev"}
        // header: {"alg":"none"} -> eyJhbGciOiJub25lIn0
        // payload: eyJlbWFpbCI6ImRldmVsb3BlckBleGFtcGxlLmNvbSIsIm5hbWUiOiJBbmRyb2lkIERldiJ9
        val dummyJwt = "eyJhbGciOiJub25lIn0.eyJlbWFpbCI6ImRldmVsb3BlckBleGFtcGxlLmNvbSIsIm5hbWUiOiJBbmRyb2lkIERldiJ9."
        val profile = GoogleAuthManager.parseUserProfile(dummyJwt)

        assertEquals("developer@example.com", profile.email)
        assertEquals("Android Dev", profile.name)
    }

    /**
     * The placeholder client id is refused, and the refusal explains itself.
     *
     * Sending it reaches Google and comes back as "Access blocked:
     * authorisation error" with no mention of a client id -- which reads like
     * the sign-in code is broken when the real state is that nobody has
     * registered an OAuth client for this package. The message has to name that,
     * because the person who hits it will otherwise debug PKCE.
     */
    @Test
    fun the_placeholder_client_id_is_refused_before_it_reaches_google() {
        val manager = GoogleAuthManager(
            keyStore = null,
            clientId = GoogleAuthManager.PLACEHOLDER_CLIENT_ID,
        )

        val failure = runCatching { manager.createAuthorizationRequest() }.exceptionOrNull()

        assertNotNull("the placeholder client id was sent to Google", failure)
        val message = failure!!.message.orEmpty()
        assertTrue("the message must say a client needs registering: $message", "Register" in message)
        assertTrue("and that a pasted API key is unaffected: $message", "API key" in message)
    }

    /**
     * The redirect must be the reverse of the client id, or Google refuses it.
     *
     * An Android OAuth client accepts exactly one custom scheme: its own id,
     * reversed. Nothing in the build checks that the two agree, and they are
     * edited in different places — the client id here, the scheme in
     * `AndroidManifest.xml` — so changing one and not the others fails at run
     * time as a browser that never comes back, with no error anywhere.
     */
    @Test
    fun the_redirect_is_the_reverse_of_the_client_id() {
        val suffix = GoogleAuthManager.DEFAULT_CLIENT_ID
            .removeSuffix(".apps.googleusercontent.com")

        assertEquals(
            "com.googleusercontent.apps.$suffix:/oauth2redirect",
            GoogleAuthManager.DEFAULT_REDIRECT_URI,
        )
    }
}
