package com.osamu.aide.engine.fast

import com.android.apksig.internal.asn1.Asn1DerEncoder
import com.android.apksig.internal.asn1.Asn1OpaqueObject
import com.android.apksig.internal.pkcs7.AlgorithmIdentifier
import com.android.apksig.internal.x509.AttributeTypeAndValue
import com.android.apksig.internal.x509.Certificate
import com.android.apksig.internal.x509.Name
import com.android.apksig.internal.x509.RelativeDistinguishedName
import com.android.apksig.internal.x509.SubjectPublicKeyInfo
import com.android.apksig.internal.x509.TBSCertificate
import com.android.apksig.internal.x509.Time
import com.android.apksig.internal.x509.Validity
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The key a *release* APK is signed with: the user's, not the device's.
 *
 * [DebugSigningKey] is deliberately the opposite of this. It lives in the
 * platform keystore, is hardware-backed where the device has a chip, and
 * **cannot be exported** -- which is right for a debug key and fatal for a
 * release one. Android identifies an app by its signing certificate, so a
 * release key has to outlive the phone it was made on: lose it and the app can
 * never be updated again, only republished under a new package name.
 *
 * So this one is an ordinary PKCS#12 file the user can copy off the device,
 * and everything here is about that file: reading a key out of one, or making
 * one that is worth backing up.
 */
internal object ReleaseSigningKey {

    /**
     * The key stored under [alias], or the only key if [alias] is null.
     *
     * **One password, because PKCS#12 has one.** The format protects the file
     * and the key with the same passphrase, which is why `keytool` refuses a
     * separate one -- *"Different store and key passwords not supported for
     * PKCS12 KeyStores"* -- and why Android's provider hands back the key for
     * any password once the file itself has opened. Modelling two would be an
     * API promising a distinction the format cannot keep, and a test asserting
     * it fails, which is how this was found.
     *
     * Throws rather than returning null, with messages meant to be read by a
     * person: "wrong password" and "no key in here" are different problems and
     * a single "could not read the keystore" leaves the user guessing.
     */
    fun load(
        keystore: File,
        password: CharArray,
        alias: String? = null,
    ): SigningKey {
        val store = KeyStore.getInstance("PKCS12")
        try {
            keystore.inputStream().use { store.load(it, password) }
        } catch (failure: Exception) {
            throw ReleaseKeyException("That is not the right password for this keystore.", failure)
        }

        val entry = alias ?: store.aliases().toList().firstOrNull {
            store.isKeyEntry(it)
        } ?: throw ReleaseKeyException("This keystore holds no signing key.")

        if (!store.isKeyEntry(entry)) {
            throw ReleaseKeyException("\"$entry\" is not a signing key in this keystore.")
        }

        val privateKey = try {
            store.getKey(entry, password) as? PrivateKey
                ?: throw ReleaseKeyException("\"$entry\" is not a private key.")
        } catch (failure: ReleaseKeyException) {
            throw failure
        } catch (failure: Exception) {
            throw ReleaseKeyException("That is not the right password for the key \"$entry\".", failure)
        }

        val certificate = store.getCertificate(entry) as? X509Certificate
            ?: throw ReleaseKeyException("\"$entry\" has no certificate, so it cannot sign.")

        return SigningKey(privateKey = privateKey, certificate = certificate)
    }

    /**
     * Makes a new keystore at [keystore] and returns the key inside it.
     *
     * **The point of this is that a phone is enough.** The project's whole
     * premise is that no desktop is required, and telling someone to go and run
     * `keytool` somewhere else to ship what they built here would undo that.
     *
     * The keypair is generated **in software**, not in the platform keystore,
     * precisely so it can be written to a file and copied off the device.
     */
    fun generate(
        keystore: File,
        password: CharArray,
        alias: String,
        commonName: String,
        years: Int = DEFAULT_VALIDITY_YEARS,
    ): SigningKey {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_SIZE, SecureRandom())
        }.generateKeyPair()

        val certificate = selfSign(keyPair, commonName, years)

        val store = KeyStore.getInstance("PKCS12").apply { load(null, password) }
        store.setKeyEntry(alias, keyPair.private, password, arrayOf(certificate))
        keystore.parentFile?.mkdirs()
        keystore.outputStream().use { store.store(it, password) }

        return SigningKey(privateKey = keyPair.private, certificate = certificate)
    }

    /**
     * A self-signed X.509 certificate for [keyPair].
     *
     * **Built with apksig's own ASN.1 encoder**, which is already on the
     * classpath because that library signs the APK a few stages later. The
     * alternatives were worse: Android exposes no public API for building a
     * certificate, its internal `sun.security.x509` is behind the hidden-API
     * restrictions, and Bouncy Castle is about 9 MB to carry for something a
     * user does once. These classes are `internal` to apksig, so this is
     * pinned to the version in the catalogue and
     * `ReleaseSigningKeyTest` is what would notice an upgrade breaking it --
     * by failing to produce a certificate the platform accepts, which is the
     * only definition of correct that matters here.
     */
    private fun selfSign(keyPair: KeyPair, commonName: String, years: Int): X509Certificate {
        val notBefore = Calendar.getInstance(UTC)
        val notAfter = Calendar.getInstance(UTC).apply { add(Calendar.YEAR, years) }

        val name = distinguishedName(commonName)
        val algorithm = AlgorithmIdentifier(SHA256_WITH_RSA_OID, Asn1DerEncoder.ASN1_DER_NULL)

        val tbs = TBSCertificate().apply {
            // v3, zero-based: the extensions field only exists from v3, and
            // some verifiers reject a v1 certificate that carries any.
            version = 2
            // Positive and unique. A random 64-bit serial is what the platform
            // tools use; a fixed one would collide with itself on a re-issue.
            serialNumber = BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE)
            signatureAlgorithm = algorithm
            issuer = name
            subject = name
            validity = Validity().apply {
                this.notBefore = asn1Time(notBefore.time)
                this.notAfter = asn1Time(notAfter.time)
            }
            subjectPublicKeyInfo = subjectPublicKeyInfo(keyPair)
        }

        val tbsDer = Asn1DerEncoder.encode(tbs)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsDer)
            sign()
        }

        val certificate = Certificate().apply {
            this.certificate = tbs
            signatureAlgorithm = algorithm
            // **A BIT STRING's contents including its leading unused-bits
            // octet.** apksig's encoder does not add it -- it writes the buffer
            // as given -- so without the zero the first byte of the signature
            // is read as a bit count and BoringSSL rejects the certificate with
            // `INVALID_BIT_STRING_BITS_LEFT`. A signature is a whole number of
            // bytes, so that count is always zero.
            this.signature = ByteBuffer.wrap(byteArrayOf(0) + signature)
        }

        // Round-tripped through the platform's own parser rather than returned
        // as bytes: if what was built is not a certificate Android will read,
        // the failure belongs here and not four stages later inside apksig.
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(Asn1DerEncoder.encode(certificate).inputStream()) as X509Certificate
    }

    /**
     * The public key half, parsed back out of the platform's own encoding.
     *
     * `PublicKey.getEncoded()` is already a DER `SubjectPublicKeyInfo`, so
     * rather than pick it apart into an algorithm and a bit string by hand,
     * this hands it to the same library's parser and gets the structure apksig
     * wants to encode. One representation, converted once, by code that
     * already agrees with itself.
     */
    private fun subjectPublicKeyInfo(keyPair: KeyPair): SubjectPublicKeyInfo =
        com.android.apksig.internal.asn1.Asn1BerParser.parse(
            ByteBuffer.wrap(keyPair.public.encoded),
            SubjectPublicKeyInfo::class.java,
        )

    /** `CN=<name>`, which is all a signing certificate needs to be valid. */
    private fun distinguishedName(commonName: String) = Name().apply {
        relativeDistinguishedNames = listOf(
            RelativeDistinguishedName().apply {
                attributes = listOf(
                    AttributeTypeAndValue().apply {
                        attrType = COMMON_NAME_OID
                        attrValue = utf8String(commonName)
                    },
                )
            },
        )
    }

    /** A DER UTF8String, tag 0x0C, which is what a modern DN uses. */
    private fun utf8String(value: String): Asn1OpaqueObject {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size < 128) { "A name this long needs long-form DER length encoding." }
        return Asn1OpaqueObject(byteArrayOf(0x0C, bytes.size.toByte()) + bytes)
    }

    /**
     * An X.509 `Time`, in whichever of its two encodings the year requires.
     *
     * **Both, because a thirty-year certificate needs both.** The standard
     * says UTCTime (`YYMMDDHHMMSSZ`, two-digit year) through 2049 and
     * GeneralizedTime (`YYYYMMDDHHMMSSZ`) from 2050 -- and a certificate
     * issued now expires in 2056, so `notBefore` and `notAfter` on the *same*
     * certificate are encoded differently. Writing UTCTime for both is the
     * obvious mistake and the one this walked into first: a two-digit "56"
     * reads as 1956, which is a certificate that expired seventy years ago.
     */
    private fun asn1Time(date: Date): Time {
        val calendar = Calendar.getInstance(UTC).apply { time = date }
        val pattern = if (calendar.get(Calendar.YEAR) < UTC_TIME_LAST_YEAR) {
            "yyMMddHHmmss'Z'"
        } else {
            "yyyyMMddHHmmss'Z'"
        }
        val formatted = SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = UTC }
            .format(date)
        return Time().apply {
            if (calendar.get(Calendar.YEAR) < UTC_TIME_LAST_YEAR) {
                utcTime = formatted
            } else {
                generalizedTime = formatted
            }
        }
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private const val KEY_SIZE = 4096

    /**
     * Thirty years, because Android refuses to install an APK whose signing
     * certificate has expired and Play requires a validity ending after 2033.
     * The same number the platform tools default to.
     */
    private const val DEFAULT_VALIDITY_YEARS = 30

    /** X.509 switches from UTCTime to GeneralizedTime at 2050. */
    private const val UTC_TIME_LAST_YEAR = 2050

    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME_OID = "2.5.4.3"
}

/** Something a person can be shown, rather than a keystore library's wording. */
internal class ReleaseKeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
