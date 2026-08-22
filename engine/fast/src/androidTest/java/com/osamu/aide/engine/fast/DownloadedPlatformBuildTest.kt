package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.awaitResult
import com.osamu.aide.toolchain.manager.InstallProgress
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole thing, with nothing staged by hand.
 *
 * Every other test in this module is handed an `android.jar` out of
 * `androidTest/assets`, which is a 27 MB file that is not in git and exists only
 * on a machine that put it there. This one downloads the platform the app
 * actually pins, stages the compile stubs the way the app does, and builds with
 * the result -- so it is the only test that would notice the two halves not
 * fitting together.
 *
 * Skipped unless asked for, because it really downloads 63 MB:
 *
 * ```
 * ./gradlew :engine:fast:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.downloadTests=true
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DownloadedPlatformBuildTest {

    @Test
    fun a_downloaded_platform_builds_a_project() = runBlocking {
        assumeTrue(
            "set -Pandroid.testInstrumentationRunnerArguments.downloadTests=true to run this",
            InstrumentationRegistry.getArguments().getString("downloadTests") == "true",
        )

        val fixture = EngineTestFixture("downloaded-platform-build-test")
        fixture.assumeAapt2Supported()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatchers = DefaultDispatcherProvider()
        val manager = ToolchainManager(context, dispatchers)
        manager.license.accept()

        val installed = manager.install(ToolchainComponent.ANDROID_PLATFORM).last()
        assertTrue("the platform did not install: $installed", installed is InstallProgress.Installed)

        // The provider stages platform-stubs.jar out of this module's assets --
        // the step the other tests skip by staging it themselves.
        val platform = AndroidPlatformProvider(context, dispatchers)
            .platformFor((installed as InstallProgress.Installed).file)
        assertTrue("the platform is incomplete: ${platform.validate()}", platform.validate() == null)

        val engine = FastBuildSystem(fixture.runner, platform, dispatchers)
        val result = engine.build(
            BuildRequest(fixture.project(), File(fixture.workDir, "out")),
        ).awaitResult()

        assertTrue("build failed: $result", result is BuildResult.Success)
        assertTrue((result as BuildResult.Success).apk.isFile)
    }
}
