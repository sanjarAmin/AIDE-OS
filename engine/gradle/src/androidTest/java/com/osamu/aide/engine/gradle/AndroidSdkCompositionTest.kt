package com.osamu.aide.engine.gradle

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Composing an SDK out of the components that are actually installable.
 *
 * There is no "Android SDK" download and there was never going to be one. What
 * `:toolchain:manager` can install is a platform and a build-tools directory,
 * each under its own component id; AGP will not look at either unless they sit
 * under one root in the layout it expects. This is that assembly, and these are
 * the claims it rests on.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSdkCompositionTest {

    private lateinit var context: Context
    private lateinit var installRoot: File
    private lateinit var provider: GradleToolchainProvider

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        installRoot = File(context.cacheDir, "components-${System.nanoTime()}").apply { mkdirs() }
        provider = GradleToolchainProvider(context, DefaultDispatcherProvider(), installRoot)
        // Composition writes into the app's real files directory, so a previous
        // run's SDK must not be what makes this one pass.
        File(context.filesDir, GradleToolchainProvider.ANDROID_SDK_HOME).deleteRecursively()
    }

    @After
    fun tearDown() {
        installRoot.deleteRecursively()
        File(context.filesDir, GradleToolchainProvider.ANDROID_SDK_HOME).deleteRecursively()
    }

    private fun installPlatform() {
        File(installRoot, GradleToolchainProvider.PLATFORM_COMPONENT_ID).mkdirs()
        File(installRoot, "${GradleToolchainProvider.PLATFORM_COMPONENT_ID}/android.jar")
            .writeText("stand-in for the platform")
    }

    private fun installBuildTools() {
        val revision = GradleToolchainProvider.BUILD_TOOLS_REVISION
        val dir = File(
            installRoot,
            "${GradleToolchainProvider.BUILD_TOOLS_COMPONENT_ID}/$revision",
        )
        dir.mkdirs()
        File(dir, "source.properties").writeText("Pkg.Revision=$revision\n")
    }

    /** Neither component installed is the ordinary state, not a failure. */
    @Test
    fun no_components_means_no_sdk() {
        assertNull(provider.androidSdk())
    }

    /**
     * A platform without build-tools is not an SDK.
     *
     * Returned as absent rather than as a half-built root, because AGP's
     * complaint about a missing build-tools directory arrives a minute into a
     * build and blames the SDK for being "corrupted".
     */
    @Test
    fun a_platform_alone_is_not_enough() {
        installPlatform()

        assertNull(provider.androidSdk())
    }

    @Test
    fun build_tools_alone_is_not_enough() {
        installBuildTools()

        assertNull(provider.androidSdk())
    }

    /**
     * With both, the layout AGP reads is assembled.
     *
     * The paths are the assertion. `platforms/android-36/android.jar` and
     * `build-tools/36.0.0` are not our naming to choose — AGP looks the
     * platform up by the API level the project's `compileSdk` names, and
     * build-tools by revision.
     */
    @Test
    fun both_components_compose_into_the_layout_agp_expects() {
        installPlatform()
        installBuildTools()

        val sdk = provider.androidSdk()

        assertTrue("the SDK should have been composed", sdk != null)
        val root = sdk!!.dir
        assertTrue(
            "the platform is not where AGP looks",
            File(root, "platforms/${GradleToolchainProvider.PLATFORM_DIRECTORY}/android.jar").exists(),
        )
        assertTrue(
            "build-tools is not where AGP looks",
            File(root, "build-tools/${GradleToolchainProvider.BUILD_TOOLS_REVISION}/source.properties")
                .exists(),
        )
        assertTrue("the composed SDK does not report itself usable", sdk.isUsable)
    }

    /**
     * The licence file is written, because AGP will not build without it.
     *
     * Its content is the hashes of the terms, which is all `sdkmanager` writes
     * when a user accepts them — every accepted revision, because the file is a
     * list and a device carrying only the older hash is refused against a
     * current SDK. This is not granting permission on the user's behalf — `:toolchain:manager` already refused to install either component
     * until the same terms were accepted; this is only how AGP is told.
     */
    @Test
    fun the_accepted_licence_is_recorded_where_agp_reads_it() {
        installPlatform()
        installBuildTools()

        val sdk = provider.androidSdk()!!

        val licence = File(sdk.dir, "licenses/android-sdk-license")
        assertTrue("no licence file was written", licence.isFile)
        assertTrue(
            "the licence file does not carry the terms' hash",
            GradleToolchainProvider.SDK_LICENCE_HASHES.all { it in licence.readText() },
        )
        assertTrue(sdk.licenceAccepted)
    }

    /**
     * Composing twice is not an error, and the second one is not stale.
     *
     * A component reinstall moves the directory the link points at, so the
     * links are rebuilt every time rather than created once. Left alone, the
     * symptom would be a build against the *previous* platform, which compiles
     * and is wrong.
     */
    @Test
    fun composing_again_after_a_reinstall_points_at_the_new_component() {
        installPlatform()
        installBuildTools()
        val first = provider.androidSdk()!!
        assertTrue(first.isUsable)

        File(installRoot, GradleToolchainProvider.PLATFORM_COMPONENT_ID).deleteRecursively()
        installPlatform()
        File(installRoot, "${GradleToolchainProvider.PLATFORM_COMPONENT_ID}/android.jar")
            .writeText("the reinstalled platform")

        val second = provider.androidSdk()!!
        assertEquals(
            "the SDK still points at the replaced component",
            "the reinstalled platform",
            File(second.dir, "platforms/${GradleToolchainProvider.PLATFORM_DIRECTORY}/android.jar")
                .readText(),
        )
    }
}
