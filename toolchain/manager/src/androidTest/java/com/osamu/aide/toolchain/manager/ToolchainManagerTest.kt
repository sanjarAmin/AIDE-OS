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
        manager.storage.remove(ToolchainComponent.KOTLIN_COMPILER)
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

    /**
     * The Kotlin compiler, downloaded from this project's own releases.
     *
     * Unlike the platform this archive is ours, and the whole point of hosting
     * it is that a user with no account can fetch it -- so this test is the one
     * that would catch a release made private, a tag moved, or an asset
     * deleted. It also exercises multi-entry extraction, which exists for this
     * component: two files out of one archive, installed as one thing.
     */
    @Test
    fun installs_the_kotlin_compiler_from_this_projects_releases() = runBlocking {
        val component = ToolchainComponent.KOTLIN_COMPILER

        val progress = manager.install(component).toList()

        val last = progress.last()
        assertTrue("install failed: $last", last is InstallProgress.Installed)

        // Both entries, not just the one the existence check looks at.
        val archive = manager.storage.fileFor(component, "kotlinc.jar")
        val stdlib = manager.storage.fileFor(component, "kotlin-stdlib.jar")
        assertTrue("kotlinc.jar was not installed", archive.isFile)
        assertTrue("kotlin-stdlib.jar was not installed", stdlib.isFile)

        // Usable, not merely present: a truncated dex archive is still a valid
        // zip, and the compiler's own classes are what it will be asked for.
        ZipFile(archive).use { zip ->
            assertTrue(
                "kotlinc.jar carries no dex",
                zip.entries().asSequence().any { it.name.endsWith(".dex") },
            )
        }
        ZipFile(stdlib).use { zip ->
            assertTrue(
                "kotlin-stdlib.jar has no kotlin/Unit.class",
                zip.getEntry("kotlin/Unit.class") != null,
            )
        }
    }

    /**
     * Kotlin is Apache-2.0; Google's SDK terms have nothing to do with it.
     *
     * Worth pinning because the licence prompt defaults to on, and asking a
     * user to accept Google's agreement before downloading a JetBrains
     * compiler would be a false statement made by the UI.
     */
    @Test
    fun the_kotlin_compiler_asks_for_no_sdk_licence() {
        assertTrue(
            "the Kotlin compiler should not require Google's SDK licence",
            !ToolchainComponent.KOTLIN_COMPILER.requiresSdkLicense,
        )
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
