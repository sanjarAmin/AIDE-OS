package com.osamu.aide.engine.fast

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** A key and the certificate that goes with it. */
internal data class SigningKey(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
)

/**
 * The key debug builds are signed with, held in the platform keystore.
 *
 * The desktop tools ship a `debug.keystore` file with a password everybody
 * knows. This generates one per device instead, which is better in three ways
 * that matter more on a phone: the private key is hardware-backed where the
 * device has a keystore chip, it never exists as a file that a backup or a file
 * manager could copy off, and there is no shipped secret to leak. The cost is
 * that it cannot be exported, so a debug APK built here cannot be updated in
 * place by one built on another device -- which is the correct behaviour for a
 * debug key anyway.
 *
 * A release key is a different problem entirely: it must survive the device, so
 * it needs an import path and a passphrase. Not modelled yet; see docs/PLAN.md.
 */
internal object DebugSigningKey {

    private const val ALIAS = "aide-debug"
    private const val KEYSTORE = "AndroidKeyStore"

    fun load(): SigningKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate()

        // Re-read rather than use what generate() returned: this is the path
        // taken on every build after the first, so it is the one worth
        // exercising every time.
        val reloaded = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return SigningKey(
            privateKey = reloaded.getKey(ALIAS, null) as PrivateKey,
            certificate = reloaded.getCertificate(ALIAS) as X509Certificate,
        )
    }

    private fun generate() {
        val notBefore = Calendar.getInstance()
        // Android refuses to install an APK whose signing certificate has
        // expired, and a phone is kept for years. Thirty is what the platform
        // tools use.
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=AIDE-OS Debug, O=AIDE-OS"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    // Deliberately no user-authentication requirement: a build
                    // that demanded a fingerprint every time would be unusable.
                    .build(),
            )
            generateKeyPair()
        }
    }
}
