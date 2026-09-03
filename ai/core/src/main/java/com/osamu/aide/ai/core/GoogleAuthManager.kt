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

        const val DEFAULT_CLIENT_ID = "1041121096058-aide-os.apps.googleusercontent.com"
        const val DEFAULT_REDIRECT_URI = "com.osamu.aide://oauth2callback"

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
