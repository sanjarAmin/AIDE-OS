package com.osamu.aide.ai.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds AI provider credentials encrypted against the Android Keystore.
 *
 * Supports multi-model providers with [AiProviderType.GEMINI] as the flagship default.
 * Credentials (Gemini API key, Google OAuth tokens, OpenAI key, Anthropic key) are
 * encrypted with hardware-backed Keystore keys and never leave the device in plaintext.
 *
 * Backward-compatible with earlier single-provider methods ([read], [save], [baseUrl]).
 */
class ApiKeyStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // -- Active Provider & Model Settings -----------------------------------

    fun activeProvider(): AiProviderType {
        val storedId = preferences.getString(KEY_ACTIVE_PROVIDER, null)
        return if (storedId != null) {
            AiProviderType.fromId(storedId)
        } else {
            // If legacy Anthropic key exists and no active provider was set, keep Anthropic
            if (preferences.contains(KEY_CIPHERTEXT)) AiProviderType.ANTHROPIC else AiProviderType.DEFAULT
        }
    }

    fun setActiveProvider(provider: AiProviderType) {
        preferences.edit().putString(KEY_ACTIVE_PROVIDER, provider.id).commit()
    }

    fun activeModel(provider: AiProviderType = activeProvider()): String {
        return preferences.getString(modelKey(provider), null) ?: provider.defaultModel
    }

    fun setActiveModel(provider: AiProviderType, model: String) {
        preferences.edit().putString(modelKey(provider), model).commit()
    }

    fun shareProjectContext(): Boolean =
        preferences.getBoolean(KEY_SHARE_CONTEXT, true)

    fun setShareProjectContext(share: Boolean) {
        preferences.edit().putBoolean(KEY_SHARE_CONTEXT, share).commit()
    }

    // -- Status Checks ------------------------------------------------------

    /** True when a key or token is saved for the active provider, or legacy key exists. */
    fun hasKey(): Boolean = when (activeProvider()) {
        AiProviderType.GEMINI -> hasGemini()
        AiProviderType.OPENAI -> hasOpenAi()
        AiProviderType.ANTHROPIC -> hasAnthropic()
        AiProviderType.CUSTOM -> hasCustom()
    }

    fun hasProviderKey(provider: AiProviderType): Boolean = when (provider) {
        AiProviderType.GEMINI -> hasGemini()
        AiProviderType.OPENAI -> hasOpenAi()
        AiProviderType.ANTHROPIC -> hasAnthropic()
        AiProviderType.CUSTOM -> hasCustom()
    }

    private fun hasGemini(): Boolean =
        preferences.contains(KEY_GEMINI_KEY_CIPHER) || preferences.contains(KEY_GOOGLE_ACCESS_TOKEN_CIPHER)

    private fun hasOpenAi(): Boolean =
        preferences.contains(KEY_OPENAI_KEY_CIPHER)

    private fun hasAnthropic(): Boolean =
        preferences.contains(KEY_CIPHERTEXT) || preferences.contains(KEY_ANTHROPIC_KEY_CIPHER)

    private fun hasCustom(): Boolean =
        preferences.contains(KEY_CUSTOM_KEY_CIPHER) || preferences.contains(KEY_CUSTOM_BASE_URL)

    // -- Legacy & Anthropic Compatibility -----------------------------------

    /**
     * Reads the active credential. If Anthropic is active (or legacy), reads the legacy key.
     * If Gemini is active, reads the Gemini API key or OAuth token.
     */
    fun read(): String? {
        return when (activeProvider()) {
            AiProviderType.ANTHROPIC -> decryptPreference(KEY_CIPHERTEXT, KEY_IV)
                ?: decryptPreference(KEY_ANTHROPIC_KEY_CIPHER, KEY_ANTHROPIC_IV)
            AiProviderType.GEMINI -> geminiApiKey() ?: googleAccessToken()
            AiProviderType.OPENAI -> openAiApiKey()
            AiProviderType.CUSTOM -> customApiKey()
        }
    }

    /** Legacy save: preserves exact test behavior and writes to legacy keys. */
    fun save(apiKey: String) {
        encryptAndStore(KEY_CIPHERTEXT, KEY_IV, apiKey)
    }

    fun baseUrl(): String? = preferences.getString(KEY_BASE_URL, null)

    fun saveBaseUrl(endpoint: Endpoint) {
        val editor = preferences.edit()
        when (endpoint) {
            is Endpoint.Custom -> editor.putString(KEY_BASE_URL, endpoint.baseUrl)
            Endpoint.Default -> editor.remove(KEY_BASE_URL)
            is Endpoint.Rejected -> return
        }
        editor.commit()
    }

    // -- Google / Gemini Credentials ----------------------------------------

    fun geminiApiKey(): String? =
        decryptPreference(KEY_GEMINI_KEY_CIPHER, KEY_GEMINI_IV)

    fun saveGeminiApiKey(key: String) {
        encryptAndStore(KEY_GEMINI_KEY_CIPHER, KEY_GEMINI_IV, key)
    }

    fun clearGeminiApiKey() {
        preferences.edit()
            .remove(KEY_GEMINI_KEY_CIPHER)
            .remove(KEY_GEMINI_IV)
            .commit()
    }

    fun isGoogleSignedIn(): Boolean =
        preferences.contains(KEY_GOOGLE_ACCESS_TOKEN_CIPHER)

    fun googleAccessToken(): String? =
        decryptPreference(KEY_GOOGLE_ACCESS_TOKEN_CIPHER, KEY_GOOGLE_ACCESS_TOKEN_IV)

    fun googleRefreshToken(): String? =
        decryptPreference(KEY_GOOGLE_REFRESH_TOKEN_CIPHER, KEY_GOOGLE_REFRESH_TOKEN_IV)

    fun googleUserEmail(): String? =
        preferences.getString(KEY_GOOGLE_USER_EMAIL, null)

    fun googleUserName(): String? =
        preferences.getString(KEY_GOOGLE_USER_NAME, null)

    fun googleGrantedScopes(): String? =
        preferences.getString(KEY_GOOGLE_GRANTED_SCOPES, null)

    fun saveGoogleOAuth(
        accessToken: String,
        refreshToken: String? = null,
        email: String? = null,
        name: String? = null,
        grantedScopes: String? = null,
    ) {
        encryptAndStore(KEY_GOOGLE_ACCESS_TOKEN_CIPHER, KEY_GOOGLE_ACCESS_TOKEN_IV, accessToken)
        if (refreshToken != null) {
            encryptAndStore(KEY_GOOGLE_REFRESH_TOKEN_CIPHER, KEY_GOOGLE_REFRESH_TOKEN_IV, refreshToken)
        }
        val editor = preferences.edit()
        if (email != null) editor.putString(KEY_GOOGLE_USER_EMAIL, email)
        if (name != null) editor.putString(KEY_GOOGLE_USER_NAME, name)
        if (grantedScopes != null) editor.putString(KEY_GOOGLE_GRANTED_SCOPES, grantedScopes)
        editor.commit()
    }

    fun signOutGoogle() {
        preferences.edit()
            .remove(KEY_GOOGLE_ACCESS_TOKEN_CIPHER)
            .remove(KEY_GOOGLE_ACCESS_TOKEN_IV)
            .remove(KEY_GOOGLE_REFRESH_TOKEN_CIPHER)
            .remove(KEY_GOOGLE_REFRESH_TOKEN_IV)
            .remove(KEY_GOOGLE_USER_EMAIL)
            .remove(KEY_GOOGLE_USER_NAME)
            .remove(KEY_GOOGLE_GRANTED_SCOPES)
            .commit()
    }

    // -- OpenAI Credentials -------------------------------------------------

    fun openAiApiKey(): String? =
        decryptPreference(KEY_OPENAI_KEY_CIPHER, KEY_OPENAI_IV)

    fun saveOpenAiApiKey(key: String) {
        encryptAndStore(KEY_OPENAI_KEY_CIPHER, KEY_OPENAI_IV, key)
    }

    fun openAiBaseUrl(): String? =
        preferences.getString(KEY_OPENAI_BASE_URL, null)

    fun saveOpenAiBaseUrl(url: String?) {
        val editor = preferences.edit()
        if (url.isNullOrBlank()) editor.remove(KEY_OPENAI_BASE_URL) else editor.putString(KEY_OPENAI_BASE_URL, url.trim())
        editor.commit()
    }

    // -- Custom Provider Credentials ----------------------------------------

    fun customApiKey(): String? =
        decryptPreference(KEY_CUSTOM_KEY_CIPHER, KEY_CUSTOM_IV)

    fun saveCustomApiKey(key: String) {
        encryptAndStore(KEY_CUSTOM_KEY_CIPHER, KEY_CUSTOM_IV, key)
    }

    fun customBaseUrl(): String? =
        preferences.getString(KEY_CUSTOM_BASE_URL, null)

    fun saveCustomBaseUrl(url: String?) {
        val editor = preferences.edit()
        if (url.isNullOrBlank()) editor.remove(KEY_CUSTOM_BASE_URL) else editor.putString(KEY_CUSTOM_BASE_URL, url.trim())
        editor.commit()
    }

    // -- Clear / Removal ----------------------------------------------------

    /** Forgets all keys. Preserves base URLs as per specification. */
    fun clear() {
        preferences.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .remove(KEY_GEMINI_KEY_CIPHER)
            .remove(KEY_GEMINI_IV)
            .remove(KEY_OPENAI_KEY_CIPHER)
            .remove(KEY_OPENAI_IV)
            .remove(KEY_ANTHROPIC_KEY_CIPHER)
            .remove(KEY_ANTHROPIC_IV)
            .remove(KEY_CUSTOM_KEY_CIPHER)
            .remove(KEY_CUSTOM_IV)
            .remove(KEY_GOOGLE_ACCESS_TOKEN_CIPHER)
            .remove(KEY_GOOGLE_ACCESS_TOKEN_IV)
            .remove(KEY_GOOGLE_REFRESH_TOKEN_CIPHER)
            .remove(KEY_GOOGLE_REFRESH_TOKEN_IV)
            .remove(KEY_GOOGLE_USER_EMAIL)
            .remove(KEY_GOOGLE_USER_NAME)
            .remove(KEY_GOOGLE_GRANTED_SCOPES)
            .commit()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    // -- Keystore AES-GCM Encryption / Decryption ---------------------------

    private fun encryptAndStore(cipherKeyPref: String, ivKeyPref: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(cipherKeyPref, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKeyPref, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    private fun decryptPreference(cipherKeyPref: String, ivKeyPref: String): String? {
        val ciphertext = preferences.getString(cipherKeyPref, null) ?: return null
        val iv = preferences.getString(ivKeyPref, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingSecretKey() ?: return null,
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingSecretKey(): SecretKey? =
        runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()

    private fun secretKey(): SecretKey = existingSecretKey() ?: generateSecretKey()

    private fun generateSecretKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "aide.ai.apikey"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        const val FILE = "aide-ai"

        const val KEY_ACTIVE_PROVIDER = "provider.active"
        const val KEY_SHARE_CONTEXT = "settings.share_context"

        // Legacy / Anthropic keys
        const val KEY_CIPHERTEXT = "apiKey.ciphertext"
        const val KEY_IV = "apiKey.iv"
        const val KEY_BASE_URL = "endpoint.baseUrl"

        // Gemini
        const val KEY_GEMINI_KEY_CIPHER = "gemini.apiKey.ciphertext"
        const val KEY_GEMINI_IV = "gemini.apiKey.iv"
        const val KEY_GOOGLE_ACCESS_TOKEN_CIPHER = "google.oauth.access.ciphertext"
        const val KEY_GOOGLE_ACCESS_TOKEN_IV = "google.oauth.access.iv"
        const val KEY_GOOGLE_REFRESH_TOKEN_CIPHER = "google.oauth.refresh.ciphertext"
        const val KEY_GOOGLE_REFRESH_TOKEN_IV = "google.oauth.refresh.iv"
        const val KEY_GOOGLE_USER_EMAIL = "google.user.email"
        const val KEY_GOOGLE_USER_NAME = "google.user.name"

        /**
         * The scopes Google granted, which can be a subset of those requested.
         *
         * Stored, not logged: a dropped scope surfaces later as
         * `ACCESS_TOKEN_SCOPE_INSUFFICIENT` naming the method and not the
         * scope, and the device this was first diagnosed on emits no logcat at
         * all. Somewhere a person can read it is the only place that works.
         */
        const val KEY_GOOGLE_GRANTED_SCOPES = "google.granted.scopes"

        // OpenAI
        const val KEY_OPENAI_KEY_CIPHER = "openai.apiKey.ciphertext"
        const val KEY_OPENAI_IV = "openai.apiKey.iv"
        const val KEY_OPENAI_BASE_URL = "openai.baseUrl"

        // Anthropic dedicated
        const val KEY_ANTHROPIC_KEY_CIPHER = "anthropic.apiKey.ciphertext"
        const val KEY_ANTHROPIC_IV = "anthropic.apiKey.iv"

        // Custom
        const val KEY_CUSTOM_KEY_CIPHER = "custom.apiKey.ciphertext"
        const val KEY_CUSTOM_IV = "custom.apiKey.iv"
        const val KEY_CUSTOM_BASE_URL = "custom.baseUrl"

        fun modelKey(provider: AiProviderType): String = "model.${provider.id}"
    }
}
