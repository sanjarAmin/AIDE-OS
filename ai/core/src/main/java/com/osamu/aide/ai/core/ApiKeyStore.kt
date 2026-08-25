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
 * Holds the user's Anthropic API key, encrypted against the Android Keystore.
 *
 * The key is the user's own credential and the most sensitive thing this app
 * will ever store: it is billable, it is bearer-authenticated, and a leak costs
 * them money rather than us. So it is encrypted with a key that **never leaves
 * the Keystore** — the secret material lives in hardware-backed storage where
 * the platform supports it, and this class only ever holds a handle to it.
 * Reading the app's preferences off a rooted device yields ciphertext.
 *
 * Written directly against the Keystore rather than using
 * `androidx.security:security-crypto`. That library is the obvious choice and
 * is deprecated upstream; this is roughly eighty lines, has no dependency to go
 * stale, and the failure mode of a wrong answer here is a leaked credential.
 *
 * **Never log the key, never put it in an exception message, never write it to
 * a file.** The tests assert the persisted form is not the plaintext, which is
 * the only way that claim can be checked rather than asserted.
 */
class ApiKeyStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True when a key has been saved. Does not decrypt, so it is cheap. */
    fun hasKey(): Boolean = preferences.contains(KEY_CIPHERTEXT)

    /**
     * The stored key, or null when there is none.
     *
     * Also null when the stored value cannot be decrypted, which is a real
     * state rather than a defect: the Keystore entry is dropped when the user
     * removes their device lock or restores a backup onto new hardware, and the
     * ciphertext then outlives the key that made it. Treated as "no key" so the
     * app asks for one again instead of crashing on launch.
     */
    fun read(): String? {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null

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
     * Encrypts and stores [apiKey], replacing anything already there.
     *
     * A fresh initialisation vector every time, because GCM is catastrophically
     * broken by IV reuse under the same key — two ciphertexts sharing an IV
     * leak the plaintext difference. The cipher generates one; this only stores
     * what it chose.
     */
    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        // commit(), not apply(). apply() returns before the write reaches disk,
        // and this is a credential the user typed once by hand: if the process
        // dies in that window they are asked for it again with no explanation.
        // A blocking write of a few hundred bytes, on an action that happens
        // about once per install, is the right side of that trade.
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    /**
     * Forgets the key entirely, Keystore entry included.
     *
     * Deleting the entry as well as the ciphertext matters: leaving the secret
     * behind would mean "signed out" still had usable key material sitting in
     * the Keystore, which is not what a user asking to remove their key means.
     */
    fun clear() {
        // commit() here for the same reason inverted: "forget my key" must be
        // on disk before the call returns, not eventually.
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
        runCatching { keyStore().deleteEntry(ALIAS) }
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
                    // Deliberately not requiring authentication per use. The
                    // key is read on every request, including background ones
                    // during a build; a biometric prompt per API call would
                    // make the feature unusable. Device-level lock still gates
                    // access to the Keystore itself.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "aide.ai.apikey"

        /** GCM, so the ciphertext is authenticated as well as secret. */
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        const val FILE = "aide-ai"
        const val KEY_CIPHERTEXT = "apiKey.ciphertext"
        const val KEY_IV = "apiKey.iv"
    }
}
