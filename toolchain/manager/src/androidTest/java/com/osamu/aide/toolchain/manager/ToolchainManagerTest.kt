package com.osamu.aide.toolchain.manager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/**
 * The pin, checked against Google.
 *
 * Skipped unless asked for. It really downloads 63 MB from dl.google.com, which
 * is not something to do on every run -- but the pinned URL, size, SHA-1 and
 * entry path are four facts about someone else's server, and nothing else in
 * the suite would notice any of them going stale:
 *
 * ```
 * ./gradlew :toolchain:manager:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.downloadTests=true
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ToolchainManagerTest {

    private lateinit var manager: ToolchainManager

    @Before
    fun setUp() {
        assumeTrue(
            "set -Pandroid.testInstrumentationRunnerArguments.downloadTests=true to run this",
            InstrumentationRegistry.getArguments().getString("downloadTests") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = ToolchainManager(context, DefaultDispatcherProvider())
        manager.storage.remove(ToolchainComponent.ANDROID_PLATFORM)
        manager.license.accept()
    }

    @Test
    fun installs_the_pinned_platform_from_googles_repository() = runBlocking {
        val component = ToolchainComponent.ANDROID_PLATFORM

        val progress = manager.install(component).toList()

        val last = progress.last()
        assertTrue("install failed: $last", last is InstallProgress.Installed)
        assertEquals(manager.androidJar(), (last as InstallProgress.Installed).file)

        // Not just present -- usable. A truncated jar opens as a valid zip with
        // classes missing, which is the failure mode the checksum exists to
        // catch, so this checks the thing the compiler will actually look for.
        val jar = requireNotNull(manager.androidJar())
        ZipFile(jar).use { zip ->
            assertTrue(
                "android.jar has no android/app/Activity.class",
                zip.getEntry("android/app/Activity.class") != null,
            )
        }
        assertTrue("the build engine still reports it cannot build", manager.canBuild())
    }

    @Test
    fun the_licence_text_is_shipped_and_is_the_agreement_it_claims_to_be() {
        val text = manager.licenseText()

        assertTrue("the licence text is missing", text.length > 10_000)
        assertTrue(
            "this is not the Android SDK agreement",
            text.contains("Android Software Development Kit License Agreement"),
        )
    }
}
