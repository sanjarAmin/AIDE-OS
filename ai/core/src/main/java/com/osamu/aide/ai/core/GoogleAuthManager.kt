package com.osamu.aide.ai.core

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Manages Google OAuth 2.0 PKCE authentication for Gemini in AIDE-OS.
 *
 * Implements RFC 7636 (Proof Key for Code Exchange) to provide secure Google Sign-In
 * on Android devices without requiring proprietary Google Play Services libraries.
 */
class GoogleAuthManager(
    private val keyStore: ApiKeyStore? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val redirectUri: String = DEFAULT_REDIRECT_URI,
) {

    data class AuthState(
        val authUrl: String,
        val codeVerifier: String,
    )

    data class UserProfile(
        val email: String?,
        val name: String?,
    )

    /**
     * Prepares the Google OAuth 2.0 PKCE authorization URL to open in a browser or Custom Tab.
     */
    fun createAuthorizationRequest(): AuthState {
        // **Refuse the placeholder rather than send it.** Google answers an
        // unregistered client id with its own error page -- "Access blocked:
        // authorisation error", no mention of a client id -- which reads like
        // the sign-in code is broken. It is not; nobody has registered a client
        // for this package yet. Failing here names the actual cause.
        check(clientId != PLACEHOLDER_CLIENT_ID) {
            "Google Sign-In needs an OAuth client registered for this package. " +
                "$PLACEHOLDER_CLIENT_ID is a placeholder, not a real client id -- a real " +
                "one ends in .apps.googleusercontent.com with a generated suffix. " +
                "Register one whose redirect is $DEFAULT_REDIRECT_URI (already declared " +
                "in the manifest) and pass it as GoogleAuthManager(clientId = ...). " +
                "Gemini by pasted API key does not need this."
        }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        lastVerifier = verifier

        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val url = "$AUTH_ENDPOINT?$query"

        return AuthState(authUrl = url, codeVerifier = verifier)
    }

    suspend fun handleRedirectUri(uriString: String): Result<UserProfile> {
        val query = uriString.substringAfter('?', "")
        val code = query.split('&').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "code") java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.firstOrNull() ?: return Result.failure(IllegalArgumentException("No code in redirect URI"))

        val verifier = lastVerifier ?: return Result.failure(IllegalStateException("No code verifier found"))
        return exchangeCodeForTokens(code, verifier)
    }

    suspend fun handleRedirectUri(uri: Uri): Result<UserProfile> = handleRedirectUri(uri.toString())

    /**
     * Exchanges an authorization code (returned from the OAuth callback) for tokens.
     */
    suspend fun exchangeCodeForTokens(
        authCode: String,
        codeVerifier: String,
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", authCode)
                .add("code_verifier", codeVerifier)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException("Token exchange failed (${response.code}): $responseBody")
            }

            val json = JSONObject(responseBody)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
            val idToken = json.optString("id_token").takeIf { it.isNotBlank() }

            val profile = parseUserProfile(idToken)

            keyStore?.saveGoogleOAuth(
                accessToken = accessToken,
                refreshToken = refreshToken,
                email = profile.email,
                name = profile.name,
            )

            profile
        }
    }

    /**
     * Refreshes the Google OAuth access token using the stored refresh token.
     */
    suspend fun refreshAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val refreshToken = keyStore?.googleRefreshToken()
                ?: throw IllegalStateException("No refresh token available")

            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException("Token refresh failed (${response.code}): $responseBody")
            }

            val json = JSONObject(responseBody)
            val newAccessToken = json.getString("access_token")

            keyStore?.saveGoogleOAuth(
                accessToken = newAccessToken,
                refreshToken = refreshToken,
                email = keyStore.googleUserEmail(),
                name = keyStore.googleUserName(),
            )

            newAccessToken
        }
    }

    companion object {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /**
         * The OAuth client registered for `com.osamu.aide`.
         *
         * Bound to the **debug** signing certificate's SHA-1, which is what an
         * Android client type means: Google checks the calling package and its
         * signature rather than a client secret, so this can sit in source
         * safely -- and so a release build signed with a different key needs
         * its own client id and will fail with this one.
         */
        const val DEFAULT_CLIENT_ID =
            "787312694223-4bhh6bk282u07553nc3iq1m7fuok6anh.apps.googleusercontent.com"

        /**
         * The value that was here before a client was registered.
         *
         * Kept so [createAuthorizationRequest] can still recognise it. Google
         * answers an unregistered client with "Access blocked: authorisation
         * error" and no mention of a client id, which reads like broken
         * sign-in code -- the check turns that into a sentence naming the
         * cause.
         */
        const val PLACEHOLDER_CLIENT_ID = "1041121096058-aide-os.apps.googleusercontent.com"

        /**
         * **Not a scheme of our choosing.** An Android OAuth client only
         * accepts the reverse of its own client id; `com.osamu.aide://...`
         * was registrable nowhere and was the redirect this code shipped with
         * before a client existed. The single slash after the colon is
         * Google's form, not a typo.
         */
        const val DEFAULT_REDIRECT_URI =
            "com.googleusercontent.apps.787312694223-4bhh6bk282u07553nc3iq1m7fuok6anh:/oauth2redirect"

        const val SCOPES = "openid email profile https://www.googleapis.com/auth/generative-language"

        @Volatile
        var lastVerifier: String? = null

        fun generateCodeVerifier(): String {
            val secureRandom = SecureRandom()
            val code = ByteArray(32)
            secureRandom.nextBytes(code)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(code)
        }

        fun generateCodeChallenge(verifier: String): String {
            val bytes = verifier.toByteArray(Charsets.US_ASCII)
            val messageDigest = MessageDigest.getInstance("SHA-256")
            messageDigest.update(bytes, 0, bytes.size)
            val digest = messageDigest.digest()
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun parseUserProfile(idToken: String?): UserProfile {
            if (idToken == null) return UserProfile(null, null)
            return runCatching {
                val parts = idToken.split(".")
                if (parts.size >= 2) {
                    val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                    val json = JSONObject(payloadJson)
                    UserProfile(
                        email = json.optString("email").takeIf { it.isNotBlank() },
                        name = json.optString("name").takeIf { it.isNotBlank() },
                    )
                } else {
                    UserProfile(null, null)
                }
            }.getOrDefault(UserProfile(null, null))
        }
    }
}
