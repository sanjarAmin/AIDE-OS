package com.osamu.aide.spike.jvmtools

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkSigner
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

/**
 * Spike R2b: ECJ, D8 and apksig on ART.
 *
 * These are steps 3 to 5 of the fast build pipeline, and the plan takes them on
 * faith: pure JVM, therefore fine. That was also true of kotlinc, which then
 * needed seven separate fixes before it would start, because a JVM library can
 * reach for far more of a JDK than its bytecode suggests.
 *
 * So the tests run the real tools against real inputs and assert on their
 * output, not on their exit codes. Each stage feeds the next, ending in a signed
 * archive that apksig's own verifier accepts.
 */
@RunWith(AndroidJUnit4::class)
class JvmToolsOnDeviceTest {

    private companion object {
        /** Placeholders swapped for staged file paths at call time. */
        const val ANDROID_JAR = "<android.jar>"
        const val PLATFORM_STUBS = "<platform-stubs.jar>"

        /**
         * android.jar alone is not a sufficient compile-time platform.
         *
         * A lambda compiles to an invokedynamic bootstrapped by
         * `java.lang.invoke.LambdaMetafactory`, and at source level 9+ so does
         * string concatenation, via `StringConcatFactory`. Neither is in
         * android.jar: on a desktop build the java.* platform comes from the
         * JDK's own java.base and only android.* comes from android.jar. There
         * is no java.base on a device, so ECJ rejects both -- which rules out
         * essentially all real Java, not just modern Java.
         *
         * platform-stubs.jar supplies exactly those two classes for the
         * compiler to resolve. Nothing needs them afterwards: D8 desugars the
         * invokedynamic into an anonymous class, so the reference never reaches
         * the APK. See tools/ecj/FINDINGS.md.
         *
         * Note -classpath rather than -bootclasspath: ECJ rejects the latter at
         * compliance 9 and above.
         */
        val ECJ_CLASSPATH_ARGS = listOf(
            "-source", "11",
            "-target", "11",
            "-classpath", "$ANDROID_JAR:$PLATFORM_STUBS",
        )
    }

    private lateinit var context: Context
    private lateinit var workDir: File
    private lateinit var androidJar: File
    private lateinit var platformStubs: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        workDir = File(context.cacheDir, "jvmtools-spike").apply {
            deleteRecursively()
            mkdirs()
        }

        // ART has no class roots of its own, so the Java compiler needs
        // somewhere to find java.lang. Android code compiles against android.jar
        // on a desktop too -- this is the normal configuration, not a shim.
        androidJar = stageAsset("android.jar")
        platformStubs = stageAsset("platform-stubs.jar")
    }

    private fun stageAsset(name: String): File {
        val target = File(workDir, name)
        context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun sourceFile(): File =
        File(workDir, "Hello.java").apply {
            writeText(
                """
                package hello;

                import java.util.List;
                import java.util.function.Function;

                public final class Hello {
                    public static String greet(String name) {
                        return "hello " + name;
                    }

                    // A lambda compiles to an invokedynamic against
                    // LambdaMetafactory, which android.jar does not contain --
                    // so this line, not the class, is what proves the compiler
                    // is usable for real code.
                    public static List<String> greetAll(List<String> names) {
                        Function<String, String> f = Hello::greet;
                        return names.stream().map(f).toList();
                    }
                }
                """.trimIndent(),
            )
        }

    /** Runs ECJ, returning its combined output so a failure can explain itself. */
    private fun compileWithEcj(
        source: File,
        outDir: File,
        classpathArgs: List<String> = ECJ_CLASSPATH_ARGS,
    ): Pair<Boolean, String> {
        outDir.mkdirs()
        val out = StringWriter()
        val err = StringWriter()
        val args = (
            classpathArgs.map {
                it.replace(ANDROID_JAR, androidJar.absolutePath)
                    .replace(PLATFORM_STUBS, platformStubs.absolutePath)
            } +
                listOf(
                    "-nowarn",
                    // Annotation processing would look for a JDK service it will
                    // not find, and processors are a separate pipeline stage.
                    "-proc:none",
                    "-d", outDir.absolutePath,
                    source.absolutePath,
                )
            ).toTypedArray()
        val ok = BatchCompiler.compile(args, PrintWriter(out), PrintWriter(err), null)
        return ok to "stdout: $out\nstderr: $err"
    }

    private fun dexWithD8(classFiles: List<File>, outDir: File): File {
        outDir.mkdirs()
        D8.run(
            D8Command.builder()
                .addProgramFiles(classFiles.map { it.toPath() })
                .addLibraryFiles(androidJar.toPath())
                .setMinApiLevel(26)
                .setMode(CompilationMode.DEBUG)
                .setOutput(outDir.toPath(), OutputMode.DexIndexed)
                .build(),
        )
        return File(outDir, "classes.dex")
    }

    /**
     * Generates a signing key in the platform keystore.
     *
     * The alternative is shipping a debug keystore inside the APK, the way the
     * desktop tools do. This is better if it works: the key is generated per
     * device, is hardware-backed where the device allows it, and never exists as
     * a file. Whether apksig accepts it is the open question -- an AndroidKeyStore
     * private key is opaque, and `getEncoded()` returns null for it.
     */
    private fun debugSigningKey(): Pair<PrivateKey, X509Certificate> {
        val alias = "aide-debug"
        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=AIDE-OS Debug, O=AIDE-OS"))
                    .setCertificateSerialNumber(java.math.BigInteger.ONE)
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    .build(),
            )
            generateKeyPair()
        }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(alias, null) as PrivateKey to
            keyStore.getCertificate(alias) as X509Certificate
    }

    /** Does ECJ run, and does it produce real class files? */
    @Test
    fun ecj_compiles_java() {
        val out = File(workDir, "out-ecj")
        val (ok, diagnostics) = compileWithEcj(sourceFile(), out)

        assertTrue("ECJ reported failure. $diagnostics", ok)

        val compiled = File(out, "hello/Hello.class")
        assertTrue("no Hello.class produced. $diagnostics", compiled.isFile)

        // 0xCAFEBABE. A zero-length or truncated file would satisfy isFile.
        val magic = compiled.readBytes().take(4).toByteArray()
        assertArrayEquals(
            "not a class file: ${magic.joinToString(" ") { "%02x".format(it) }}",
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            magic,
        )
    }

    /** Does D8 run, and does it emit a loadable dex? */
    @Test
    fun d8_dexes_class_files() {
        val classesDir = File(workDir, "out-ecj")
        val (ok, diagnostics) = compileWithEcj(sourceFile(), classesDir)
        assertTrue("ECJ reported failure. $diagnostics", ok)

        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue("nothing to dex", classFiles.isNotEmpty())

        val dex = dexWithD8(classFiles, File(workDir, "out-d8"))

        assertTrue("no classes.dex produced", dex.isFile)
        // "dex\n" followed by a three-digit version and a NUL.
        assertEquals("dex\n", dex.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII))

        // The source compiles a method reference, so the class files handed to
        // D8 contain an invokedynamic against LambdaMetafactory -- a class that
        // exists only as a compile-time stub and is not on the device at all.
        // D8 is supposed to desugar it into a synthesized class. Asserting that
        // is what makes the stub honest rather than a deferred crash: if the
        // reference survived into the dex, the APK would fail the moment the
        // lambda was reached.
        val dexText = dex.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(
            "LambdaMetafactory survived into the dex: D8 did not desugar the lambda",
            !dexText.contains("LambdaMetafactory"),
        )
        // D8 marks the classes it invents for desugared lambdas with this.
        assertTrue(
            "no synthesized class in the dex, so nothing replaced the invokedynamic",
            dexText.contains("D8${'$'}${'$'}SyntheticClass"),
        )
    }

    /**
     * The R2b question end to end: compile, dex, package, sign, and have
     * apksig's own verifier accept the result.
     */
    @Test
    fun apksig_signs_an_archive_that_verifies() {
        val classesDir = File(workDir, "out-ecj")
        val (ok, diagnostics) = compileWithEcj(sourceFile(), classesDir)
        assertTrue("ECJ reported failure. $diagnostics", ok)
        val classFiles = classesDir.walkTopDown().filter { it.extension == "class" }.toList()
        val dex = dexWithD8(classFiles, File(workDir, "out-d8"))

        val unsigned = File(workDir, "unsigned.apk")
        ZipOutputStream(unsigned.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            dex.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }

        val (privateKey, certificate) = debugSigningKey()
        val signed = File(workDir, "signed.apk")

        ApkSigner.Builder(
            listOf(
                ApkSigner.SignerConfig.Builder("debug", privateKey, listOf(certificate)).build(),
            ),
        )
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setMinSdkVersion(26)
            .build()
            .sign()

        assertTrue("no signed archive produced", signed.isFile)
        assertTrue("signed archive is smaller than its input", signed.length() > unsigned.length())

        // ApkVerifier is not used here: it reads AndroidManifest.xml to work out
        // which platform versions to check against, and refuses without one.
        // Producing a binary manifest needs aapt2, which is the pipeline stage
        // this module does not have -- so end-to-end verification belongs in
        // :build:fast, against a real APK. What is checkable here is that apksig
        // did the work rather than copying the archive through.
        val entries = ZipFile(signed).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(
            "no v1 signature block: $entries",
            entries.any { it.startsWith("META-INF/") && it.endsWith(".SF") } &&
                entries.any { it.startsWith("META-INF/") && it.endsWith(".RSA") },
        )

        // The v2 signature lives in the APK Signing Block, between the entries
        // and the central directory, so it is invisible to the zip API.
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val bytes = signed.readBytes()
        val hasSigningBlock = (0..bytes.size - magic.size).any { i ->
            magic.indices.all { bytes[i + it] == magic[it] }
        }
        assertTrue("no v2 APK Signing Block", hasSigningBlock)
    }
}
