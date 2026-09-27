package com.osamu.aide.vcs.git

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
 * Holds host access tokens, encrypted against the Android Keystore.
 *
 * Spike R6 found there is no credential helper and no `~/.gitconfig` on a
 * device, so whatever stores a token is ours. The same reasoning as
 * `ApiKeyStore` applies and applies harder: **a git token is usually broader
 * in scope than an API key** — a GitHub PAT with `repo` can read and rewrite
 * every repository the user can reach, and unlike a billing credential the
 * damage is not measured in money.
 *
 * Written directly against the Keystore rather than using
 * `androidx.security:security-crypto`, which is deprecated upstream. The
 * mechanics are `ApiKeyStore`'s, deliberately: AES/GCM, a fresh IV per write,
 * the secret never leaving the Keystore, `commit()` rather than `apply()`.
 * Duplicated rather than shared because `:vcs:git` must not depend on
 * `:ai:core` — they have nothing to do with each other, and a common
 * `:core:crypto` for two callers would be an abstraction invented before its
 * second use rather than at it.
 *
 * Keyed by **host**, not by repository. A token is issued by a hosting
 * provider and works across every repository on it, so storing one per clone
 * would ask the user for the same string repeatedly and leave copies of it
 * behind when a clone is deleted.
 *
 * **Never log a token, never put one in an exception message.** JGit's own
 * transport exceptions can carry the remote URL; anything surfacing one to the
 * user should go through [redact].
 */
class GitCredentialStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True when a token is stored for [host]. Does not decrypt, so it is cheap. */
    fun hasToken(host: String): Boolean = preferences.contains(ciphertextKey(host))

    /** Every host with a stored token, for a settings screen to list. */
    fun hosts(): List<String> = preferences.all.keys
        .filter { it.endsWith(CIPHERTEXT_SUFFIX) }
        .map { it.removePrefix(PREFIX).removeSuffix(CIPHERTEXT_SUFFIX) }
        .sorted()

    /**
     * The token for [host], or null when there is none.
     *
     * Also null when the stored value cannot be decrypted, which is a real
     * state rather than a defect: the Keystore entry is dropped when the user
     * removes their device lock or restores a backup onto new hardware, and the
     * ciphertext then outlives the key that made it. Treated as "no token" so
     * the app asks again instead of failing a push with a decryption error.
     */
    fun read(host: String): String? {
        val ciphertext = preferences.getString(ciphertextKey(host), null) ?: return null
        val iv = preferences.getString(ivKey(host), null) ?: return null

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

    /**
     * Encrypts and stores [token] for [host], replacing anything already there.
     *
     * A fresh initialisation vector every time, because GCM is catastrophically
     * broken by IV reuse under one key: two ciphertexts sharing an IV leak the
     * plaintext difference. The cipher chooses one; this only stores it.
     */
    fun save(host: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(ciphertextKey(host), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey(host), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    /**
     * Forgets one host's token.
     *
     * The Keystore secret is shared across hosts, so it survives -- deleting it
     * here would silently invalidate every other host's stored token, which is
     * not what removing one of them means. [clear] is where it goes.
     */
    fun forget(host: String) {
        preferences.edit().remove(ciphertextKey(host)).remove(ivKey(host)).commit()
    }

    /**
     * Forgets every token, Keystore entry included.
     *
     * Deleting the entry as well as the ciphertexts matters: leaving the secret
     * behind would mean "signed out" still had usable key material sitting in
     * the Keystore.
     */
    fun clear() {
        preferences.edit().clear().commit()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun ciphertextKey(host: String) = "$PREFIX${host.lowercase()}$CIPHERTEXT_SUFFIX"
    private fun ivKey(host: String) = "$PREFIX${host.lowercase()}$IV_SUFFIX"

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
                    // Not per-use authentication: a fetch can happen while the
                    // screen is off, and a biometric prompt per network round
                    // trip would make background sync impossible. Device-level
                    // lock still gates the Keystore itself.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()

    companion object {
        /**
         * The host a remote URL's credential belongs to, or null.
         *
         * Lowercased because hosts are case-insensitive and a token stored for
         * `GitHub.com` must be found for `github.com`. Port included when the
         * URL names one: a self-hosted instance on a non-default port is a
         * different endpoint with a different token.
         */
        fun hostOf(remoteUrl: String): String? {
            val uri = runCatching { java.net.URI(remoteUrl.trim()) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
            return if (uri.port != -1) "$host:${uri.port}" else host
        }

        /**
         * Removes any credential embedded in a URL before it is shown or logged.
         *
         * JGit's transport exceptions quote the URL they were given, and a URL
         * of the form `https://token@host/repo.git` is a shape users paste. So
         * every path from a transport failure to a screen or a log has to pass
         * through here -- not because this module builds such URLs, but because
         * it cannot stop a user from typing one.
         */
        fun redact(text: String): String = CREDENTIAL_IN_URL.replace(text, "$1***@")

        /** `scheme://` then anything up to an `@` that precedes a host. */
        private val CREDENTIAL_IN_URL = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s]+@""")

        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "aide.vcs.git.token"

        /** GCM, so a stored token is authenticated as well as secret. */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        private const val FILE = "aide-git-credentials"
        private const val PREFIX = "token."
        private const val CIPHERTEXT_SUFFIX = ".ciphertext"
        private const val IV_SUFFIX = ".iv"
    }
}
