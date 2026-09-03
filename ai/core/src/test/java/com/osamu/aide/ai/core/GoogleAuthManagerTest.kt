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
}
