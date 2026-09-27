package com.osamu.aide.engine.gradle

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the engine has to arrange before AGP will build, tested without AGP.
 *
 * Deliberately separate from [GradleBuildSystemTest], which needs a JDK, a
 * Gradle distribution and an SDK staged out of band and skips without them.
 * None of that is needed to answer whether we write the right path into the
 * right file, and a rule that only runs on a fully staged machine is a rule
 * that is unchecked on every other one.
 *
 * Instrumented rather than a JVM test because symlink creation and
 * `nativeLibraryDir` are both platform behaviour, and this module's whole
 * subject is what the platform will and will not accept.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSdkTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var sdkDir: File
    private lateinit var linkDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "sdk-test-${System.nanoTime()}").apply { mkdirs() }
        sdkDir = File(root, "sdk").apply { mkdirs() }
        linkDir = File(root, "bin")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun sdk(aapt2: File = bundledAapt2()) =
        AndroidSdk(dir = sdkDir, bundledAapt2 = aapt2, linkDir = linkDir)

    private fun bundledAapt2() = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")

    private fun installPlatform(api: String = "android-36") {
        File(sdkDir, "platforms/$api").mkdirs()
        File(sdkDir, "platforms/$api/android.jar").writeText("not really a jar")
    }

    private fun acceptLicence() {
        File(sdkDir, "licenses").mkdirs()
        File(sdkDir, "licenses/android-sdk-license").writeText("8933bad161af4178b1185d1a37fbf41ea5269c55\n")
    }

    // -- local.properties ----------------------------------------------------

    /**
     * An imported project's `sdk.dir` is corrected, and nothing else is lost.
     *
     * Not a hypothetical: every project checked out from a repository or
     * imported from a desktop carries a `local.properties` naming an SDK path
     * that does not exist here, and AGP prefers it to `ANDROID_HOME`. A build
     * that respected it would fail on a path belonging to someone else's
     * machine — while M9's acceptance test is *specifically* about building an
     * unmodified Android Studio project.
     *
     * The other keys matter as much as the corrected one. A project may keep
     * its NDK path or a signing location here, and taking those away to fix the
     * SDK path trades one broken build for another.
     */
    @Test
    fun a_stale_sdk_path_is_corrected_and_the_other_keys_are_kept() {
        val properties = File(root, "local.properties")
        properties.writeText("sdk.dir=/home/someone/Android/Sdk\nndk.dir=/home/someone/ndk\n")

        sdk().pointProjectAtSdk(root)

        assertEquals(
            listOf("ndk.dir=/home/someone/ndk", "sdk.dir=${sdkDir.absolutePath}"),
            properties.readLines().filter { it.isNotBlank() },
        )
    }

    /** A project with no local.properties gets one rather than an error. */
    @Test
    fun a_project_without_local_properties_gets_one() {
        sdk().pointProjectAtSdk(root)

        assertEquals(
            listOf("sdk.dir=${sdkDir.absolutePath}"),
            File(root, "local.properties").readLines().filter { it.isNotBlank() },
        )
    }

    /** Building twice must not accumulate a file full of sdk.dir lines. */
    @Test
    fun pointing_at_the_sdk_twice_leaves_one_entry() {
        sdk().pointProjectAtSdk(root)
        sdk().pointProjectAtSdk(root)

        val lines = File(root, "local.properties").readLines().filter { it.isNotBlank() }
        assertEquals("sdk.dir was written twice: $lines", 1, lines.size)
    }

    /**
     * `gradle.properties` is never touched.
     *
     * It is checked into the user's repository and holds their own settings, so
     * the aapt2 override goes on the command line instead. This is the
     * assertion that keeps it there.
     */
    @Test
    fun the_users_gradle_properties_is_left_alone() {
        val gradleProperties = File(root, "gradle.properties")
        gradleProperties.writeText("org.gradle.caching=true\n")

        sdk().pointProjectAtSdk(root)

        assertEquals("org.gradle.caching=true", gradleProperties.readText().trim())
    }

    // -- aapt2 ---------------------------------------------------------------

    /**
     * The override is named `aapt2` and really is our binary.
     *
     * AGP rejects an override whose file is named anything else, before ever
     * executing it, so the link's *name* is load-bearing — asserting only the
     * path would pass with the `.so` it points at.
     */
    @Test
    fun the_aapt2_override_is_a_link_named_aapt2_pointing_at_our_binary() {
        val link = sdk().aapt2Override()

        assertTrue("no aapt2 override was produced", link != null)
        assertEquals("aapt2", link!!.name)
        assertEquals(bundledAapt2().canonicalPath, link.canonicalPath)
    }

    /**
     * The link is refreshed, not reused.
     *
     * `nativeLibraryDir` moves when the app is updated, and a link left over
     * from the previous install points at a path that no longer exists. The
     * symptom is AGP reporting a missing aapt2 on a device where aapt2 is
     * plainly installed.
     */
    @Test
    fun a_stale_link_is_replaced_rather_than_kept() {
        linkDir.mkdirs()
        val stale = File(linkDir, "aapt2")
        stale.writeText("left over from a previous install")

        val link = sdk().aapt2Override()

        assertEquals(bundledAapt2().canonicalPath, link!!.canonicalPath)
    }

    /** No bundled aapt2 is a device below the toolchain floor, not a crash. */
    @Test
    fun no_bundled_aapt2_yields_no_override() {
        assertNull(sdk(aapt2 = File(root, "does-not-exist.so")).aapt2Override())
    }

    // -- readiness -----------------------------------------------------------

    /**
     * The licence is checked here rather than left to AGP.
     *
     * AGP does say so itself — after Gradle has started, configured and begun
     * executing, which on this hardware is most of a minute before the user
     * learns they needed to tap a button.
     */
    @Test
    fun an_sdk_without_an_accepted_licence_is_not_usable() {
        installPlatform()

        assertFalse(sdk().isUsable)
        assertTrue("the platform itself should still be found", sdk().platformJar != null)
    }

    @Test
    fun an_sdk_without_a_platform_is_not_usable() {
        acceptLicence()

        assertNull(sdk().platformJar)
        assertFalse(sdk().isUsable)
    }

    @Test
    fun a_platform_and_an_accepted_licence_is_usable() {
        installPlatform()
        acceptLicence()

        assertTrue(sdk().isUsable)
    }

    /** With several platforms installed, the newest is what we compile against. */
    @Test
    fun the_newest_installed_platform_is_chosen() {
        installPlatform("android-34")
        installPlatform("android-36")
        acceptLicence()

        assertEquals("android-36", sdk().platformJar!!.parentFile!!.name)
    }
}
