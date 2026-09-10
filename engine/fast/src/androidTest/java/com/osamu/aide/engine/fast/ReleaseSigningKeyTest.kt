package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The release key: made on the device, and good enough for Android.
 *
 * The certificate here is **built by hand** out of apksig's internal ASN.1
 * encoder, because Android exposes no public API for making one and Bouncy
 * Castle is 9 MB to carry for something a user does once
 * (`ReleaseSigningKey.selfSign`). That is a bold enough thing to do that the
 * tests have to be about the certificate itself rather than about the code
 * that wrote it: every assertion below is the platform's own parser, or
 * `Signature`, agreeing that what came out is real.
 *
 * The end-to-end proof -- that an APK signed with one of these installs --
 * lives in `ApkInstallerTest`, because that is where an installer already is.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSigningKeyTest {

    private lateinit var dir: File

    /** One, because PKCS#12 has one; see `ReleaseSigningKey.load`. */
    private val password = "keystore-secret".toCharArray()

    @Before
    fun setUp() {
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "release-signing-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun generate(commonName: String = "AIDE-OS Test") = ReleaseSigningKey.generate(
        keystore = File(dir, "release.p12"),
        password = password,
        alias = "release",
        commonName = commonName,
    )

    /**
     * The platform parses it, and the signature on it verifies.
     *
     * `CertificateFactory` is not lenient about a malformed body -- a wrong
     * length or a misplaced tag fails here -- and `verify` with the key's own
     * public half is what makes it *self*-signed rather than merely
     * self-describing.
     */
    @Test
    fun the_certificate_it_builds_is_one_android_will_parse_and_verify() {
        val key = generate()

        val certificate = key.certificate
        certificate.verify(certificate.publicKey)
        certificate.checkValidity(Date())

        assertEquals("X.509", certificate.type)
        // v3, which is what the extensions field requires and what the tools emit.
        assertEquals(3, certificate.version)
        assertTrue("the subject is not the name asked for: ${certificate.subjectX500Principal}",
            certificate.subjectX500Principal.name.contains("AIDE-OS Test"))
        assertEquals(
            "a self-signed certificate's issuer and subject must match",
            certificate.subjectX500Principal,
            certificate.issuerX500Principal,
        )
        assertEquals("SHA256withRSA", certificate.sigAlgName)
    }

    /**
     * It outlives the phone, which is the whole reason this is not the debug
     * key. Android refuses to install an APK whose certificate has expired, and
     * a release key that dies in a year takes the app's update path with it.
     */
    @Test
    fun the_certificate_is_valid_for_decades() {
        val certificate = generate().certificate

        val years = (certificate.notAfter.time - certificate.notBefore.time) /
            (365.25 * 24 * 60 * 60 * 1000)
        assertTrue("only $years years of validity", years > 25)
    }

    /**
     * And the private key really is in the file, readable with the passwords
     * that made it. This is the half `DebugSigningKey` cannot do at all.
     */
    @Test
    fun the_key_survives_a_round_trip_through_the_file() {
        val generated = generate()

        val loaded = ReleaseSigningKey.load(
            keystore = File(dir, "release.p12"),
            password = password,
        )

        assertEquals(generated.certificate, loaded.certificate)
        assertEquals(generated.privateKey.algorithm, loaded.privateKey.algorithm)
        // The bytes, not the object: a PKCS#12 round trip rebuilds the key, so
        // identity would pass for the wrong reason.
        assertTrue(generated.privateKey.encoded.contentEquals(loaded.privateKey.encoded))
    }

    /** A keystore this app wrote is one `keytool` and Gradle can also read. */
    @Test
    fun the_file_it_writes_is_an_ordinary_pkcs12_keystore() {
        generate()

        val store = KeyStore.getInstance("PKCS12")
        File(dir, "release.p12").inputStream().use { store.load(it, password) }

        assertEquals(listOf("release"), store.aliases().toList())
        assertTrue(store.isKeyEntry("release"))
        assertTrue(store.getCertificate("release") is X509Certificate)
    }

    /**
     * Two keys are two identities.
     *
     * Serial numbers are random per certificate; two generated in the same
     * millisecond must still differ, or an app signed with one could be
     * confused for an app signed with the other.
     */
    @Test
    fun two_generated_keys_are_different_identities() {
        val first = generate().certificate
        dir.resolve("release.p12").delete()
        val second = generate().certificate

        assertNotEquals(first.serialNumber, second.serialNumber)
        assertNotEquals(first.publicKey, second.publicKey)
    }

    /**
     * A wrong password is refused, and says so in words a person can act on.
     *
     * **There is only one password to get wrong.** The first version of this
     * test asserted that a wrong *key* password was told apart from a wrong
     * *store* password, and it failed by loading the key anyway -- because
     * PKCS#12 protects both with the same passphrase. `keytool` says the same
     * thing out loud when asked for two. The API models one now.
     */
    @Test
    fun a_wrong_password_is_refused_in_words() {
        generate()

        val failure = runCatching {
            ReleaseSigningKey.load(File(dir, "release.p12"), "nope".toCharArray())
        }.exceptionOrNull()

        assertTrue("a wrong password was accepted", failure != null)
        assertTrue(
            "the message does not mention the password: ${failure?.message}",
            failure?.message?.contains("password") == true,
        )
    }
}
