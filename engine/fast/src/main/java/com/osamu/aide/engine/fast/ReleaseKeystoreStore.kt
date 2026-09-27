package com.osamu.aide.engine.fast

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the settings screen shows about the key, without showing the key. */
data class ReleaseKeyInfo(
    val alias: String,
    /** The certificate's subject, which is whatever name the user gave it. */
    val commonName: String,
    val expiresOn: Date,
    /** True when the passphrase is remembered, so a build will not ask. */
    val passphraseRemembered: Boolean,
)

/**
 * Where the release key lives on the device, and whether its passphrase does.
 *
 * **The keystore file is the user's property, not the app's.** It is kept in
 * app-private storage so nothing else can read it, and [exportTo] exists
 * because a release key that cannot be backed up is a release key that will be
 * lost -- and losing it means the app can never be updated again, only
 * republished under a new package name. Everything here is arranged around
 * making that copy easy to take.
 *
 * The passphrase is optional to remember and encrypted with a key held in the
 * platform keystore when it is, which is the same arrangement `ApiKeyStore`
 * uses for provider credentials. Not remembering it is a supported answer, not
 * a degraded one: the build asks instead.
 */
class ReleaseKeystoreStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val keystore = File(context.filesDir, "signing/release.p12")

    /** True when a keystore has been imported or generated. */
    fun isConfigured(): Boolean = keystore.isFile

    fun hasRememberedPassphrase(): Boolean = rememberedPassphrase() != null

    /**
     * What to show about the key, or null when there is none or it cannot be
     * opened without a passphrase this does not have.
     */
    fun describe(): ReleaseKeyInfo? {
        if (!isConfigured()) return null
        val passphrase = rememberedPassphrase() ?: return null
        return runCatching {
            val key = ReleaseSigningKey.load(keystore, passphrase, alias())
            ReleaseKeyInfo(
                alias = alias() ?: UNKNOWN_ALIAS,
                commonName = key.certificate.subjectX500Principal.name
                    .substringAfter("CN=", UNKNOWN_ALIAS)
                    .substringBefore(','),
                expiresOn = key.certificate.notAfter,
                passphraseRemembered = true,
            )
        }.getOrNull()
    }

    /**
     * Copies [source] in after checking the passphrase opens it.
     *
     * Validated before it is stored, deliberately: a keystore saved without
     * being opened is a build that fails much later, with the user having
     * forgotten which of the two things they typed was wrong.
     */
    fun import(source: InputStream, passphrase: CharArray, remember: Boolean) {
        val staged = File(keystore.parentFile, "import.tmp").apply { parentFile?.mkdirs() }
        try {
            staged.outputStream().use { source.copyTo(it) }
            // Throws ReleaseKeyException with something a person can act on.
            val key = ReleaseSigningKey.load(staged, passphrase)
            keystore.parentFile?.mkdirs()
            staged.copyTo(keystore, overwrite = true)
            storeAlias(aliasOf(staged, passphrase))
            rememberPassphrase(passphrase, remember)
            key
        } finally {
            staged.delete()
        }
    }

    /** Makes a new key here, and remembers the passphrase if asked. */
    fun generate(passphrase: CharArray, commonName: String, remember: Boolean) {
        ReleaseSigningKey.generate(
            keystore = keystore,
            password = passphrase,
            alias = DEFAULT_ALIAS,
            commonName = commonName,
        )
        storeAlias(DEFAULT_ALIAS)
        rememberPassphrase(passphrase, remember)
    }

    /**
     * Writes the keystore to [sink], byte for byte.
     *
     * The whole point of the file, and the reason the generate path is
     * defensible at all: what comes out here is a PKCS#12 that `keytool`,
     * Gradle and Android Studio all read.
     */
    fun exportTo(sink: OutputStream) {
        require(isConfigured()) { "There is no release key to export." }
        keystore.inputStream().use { it.copyTo(sink) }
    }

    /**
     * Forgets the key entirely.
     *
     * Deliberately loud in the UI that calls it: this is the one action here
     * that can cost someone the ability to update an app they have published,
     * and the file is not recoverable afterwards.
     */
    fun remove() {
        keystore.delete()
        preferences.edit().clear().commit()
        runCatching { platformKeyStore().deleteEntry(SECRET_ALIAS) }
    }

    /** Remembers, or forgets, the passphrase for later builds. */
    fun rememberPassphrase(passphrase: CharArray, remember: Boolean) {
        if (!remember) {
            preferences.edit().remove(KEY_PASSPHRASE).remove(KEY_IV).commit()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(String(passphrase).toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    /**
     * The key a release build signs with.
     *
     * [passphrase] is what the user just typed, or null to use the remembered
     * one. Throws [ReleaseKeyException] when there is neither, so the build
     * refuses by name rather than failing inside apksig.
     */
    internal fun signingKey(passphrase: CharArray? = null): SigningKey {
        if (!isConfigured()) {
            throw ReleaseKeyException("There is no release signing key. Add one in Settings.")
        }
        val secret = passphrase ?: rememberedPassphrase()
            ?: throw ReleaseKeyException("This release key's passphrase is not remembered.")
        return ReleaseSigningKey.load(keystore, secret, alias())
    }

    private fun aliasOf(file: File, passphrase: CharArray): String? = runCatching {
        KeyStore.getInstance("PKCS12")
            .apply { file.inputStream().use { load(it, passphrase) } }
            .aliases().toList().firstOrNull()
    }.getOrNull()

    private fun alias(): String? = preferences.getString(KEY_ALIAS, null)

    private fun storeAlias(alias: String?) {
        preferences.edit().putString(KEY_ALIAS, alias).commit()
    }

    private fun rememberedPassphrase(): CharArray? {
        val ciphertext = preferences.getString(KEY_PASSPHRASE, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingSecretKey() ?: return null,
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
                .toCharArray()
        }.getOrNull()
    }

    private fun platformKeyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingSecretKey(): SecretKey? =
        runCatching { platformKeyStore().getKey(SECRET_ALIAS, null) as? SecretKey }.getOrNull()

    private fun secretKey(): SecretKey = existingSecretKey()
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    SECRET_ALIAS,
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
        const val SECRET_ALIAS = "aide.signing.passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        const val FILE = "aide-signing"
        const val KEY_PASSPHRASE = "passphrase.ciphertext"
        const val KEY_IV = "passphrase.iv"
        const val KEY_ALIAS = "keystore.alias"

        const val DEFAULT_ALIAS = "release"
        const val UNKNOWN_ALIAS = "release"
    }
}
