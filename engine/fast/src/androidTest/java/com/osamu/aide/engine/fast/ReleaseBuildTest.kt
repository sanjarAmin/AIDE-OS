package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.awaitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * A release build is signed with the user's key, and the APK proves it.
 *
 * The half `ReleaseSigningKeyTest` cannot reach: that one shows the
 * certificate is well formed, this one shows the whole pipeline uses it --
 * aapt2 through apksig -- and that what comes out carries **that** identity
 * rather than the device's debug key. Android decides whether two APKs are the
 * same app by comparing exactly this, so it is the assertion that matters.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseBuildTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var keys: ReleaseKeystoreStore

    private val passphrase = "release-secret".toCharArray()

    @Before
    fun setUp() {
        fixture = EngineTestFixture("release-build-test")
        fixture.assumeAapt2Supported()
        keys = ReleaseKeystoreStore(fixture.context).apply { remove() }
    }

    @After
    fun tearDown() {
        keys.remove()
    }

    private fun build(debuggable: Boolean): BuildResult {
        val engine = FastBuildSystem(
            runner = fixture.runner,
            platform = fixture.platform,
            dispatchers = DefaultDispatcherProvider(),
            releaseKeys = keys,
        )
        return runBlocking {
            engine.build(
                BuildRequest(
                    project = fixture.project(),
                    outputDir = File(fixture.workDir, if (debuggable) "debug" else "release"),
                    debuggable = debuggable,
                ),
            ).awaitResult()
        }
    }

    /**
     * The certificate an APK is signed with, read out of its v1 signature.
     *
     * `JarFile(file, verify = true)` and reading the entry to its end are both
     * required: the certificates are a side effect of verification, and an
     * entry that has not been consumed reports none. `SigningStage` enables v1
     * alongside v2 and v3, so the JAR signature is there to read.
     */
    private fun signerOf(apk: File): X509Certificate = JarFile(apk, true).use { jar ->
        val entry = jar.getJarEntry("AndroidManifest.xml")
        jar.getInputStream(entry).use { it.readBytes() }
        entry.certificates.orEmpty().filterIsInstance<X509Certificate>().first()
    }

    /**
     * The whole point: a release APK carries the user's certificate.
     *
     * Asserted against the debug build of the same project, because "not the
     * debug key" is the claim -- an APK signed with the device key would pass
     * every other check here and be the exact bug this feature exists to
     * prevent.
     */
    @Test
    fun a_release_build_is_signed_with_the_users_key_and_a_debug_build_is_not() {
        keys.generate(passphrase, commonName = "Release Test", remember = true)
        val expected = ReleaseSigningKey.load(
            File(fixture.context.filesDir, "signing/release.p12"),
            passphrase,
        ).certificate

        val release = build(debuggable = false)
        assertTrue("release build failed: $release", release is BuildResult.Success)
        val releaseApk = (release as BuildResult.Success).apk

        val debug = build(debuggable = true)
        assertTrue("debug build failed: $debug", debug is BuildResult.Success)
        val debugApk = (debug as BuildResult.Success).apk

        assertEquals(
            "the release APK is not signed with the release key",
            expected,
            signerOf(releaseApk),
        )
        assertNotEquals(
            "the debug APK was signed with the release key",
            expected,
            signerOf(debugApk),
        )
    }

    /**
     * With no key at all the build is refused by name, before any stage runs.
     *
     * The wrong behaviours this rules out are both quiet: signing it with the
     * debug key anyway -- which would publish an app under the device's
     * throwaway identity -- or failing somewhere inside apksig with a message
     * about a null key.
     */
    @Test
    fun a_release_build_without_a_key_is_refused_and_says_where_to_get_one() {
        val result = build(debuggable = false)

        assertTrue("expected a refusal, got: $result", result is BuildResult.Failure)
        val message = (result as BuildResult.Failure).message
        assertTrue("the refusal does not mention Settings: $message", message.contains("Settings"))
    }

    /**
     * A key whose passphrase is not remembered is a different refusal, because
     * it has a different answer: nothing to install, just something to type.
     */
    @Test
    fun a_release_build_without_the_passphrase_says_so_separately() {
        keys.generate(passphrase, commonName = "Release Test", remember = false)

        val result = build(debuggable = false)

        assertTrue("expected a refusal, got: $result", result is BuildResult.Failure)
        val message = (result as BuildResult.Failure).message
        assertTrue("the refusal does not mention the passphrase: $message",
            message.contains("passphrase"))
    }
}
