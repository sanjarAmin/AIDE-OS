package com.osamu.aide.engine.fast

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.awaitResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The last step of M2: the APK the engine built is one the platform will
 * actually take.
 *
 * Installing needs the user, so the automated assertion stops where the user
 * begins -- at the point the platform has read the archive into a session,
 * committed it, and asked for confirmation. A malformed APK never gets that far:
 * commit answers with STATUS_FAILURE_INVALID instead. That makes this a real
 * check on the packaging and signing stages, not just on this class.
 *
 * The install is then completed for real through `pm install`, which needs no
 * confirmation because it runs as shell.
 */
@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var installer: ApkInstaller
    private lateinit var apk: File
    private var appOpsOutput: String = ""

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The package the built APK declares, not the test's own. */
    private val builtPackage = "com.example.demo"

    @Before
    fun setUp() = runBlocking {
        fixture = EngineTestFixture("apk-installer-test")
        fixture.assumeAapt2Supported()

        val context = fixture.context
        installer = ApkInstaller(context, DefaultDispatcherProvider())

        // REQUEST_INSTALL_PACKAGES is a user toggle, not a runtime permission,
        // so it cannot be granted the usual way. appops is how the platform's
        // own tests do it.
        appOpsOutput = shell("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow") +
            shell("appops get ${context.packageName} REQUEST_INSTALL_PACKAGES")
        shell("pm uninstall $builtPackage")

        val engine = FastBuildSystem(fixture.runner, fixture.platform, DefaultDispatcherProvider())
        val result = engine.build(
            BuildRequest(fixture.project(), File(fixture.workDir, "out")),
        ).awaitResult()
        assertTrue("build failed: $result", result is BuildResult.Success)
        apk = (result as BuildResult.Success).apk
    }

    @After
    fun tearDown() {
        shell("pm uninstall $builtPackage")
    }

    @Test
    fun the_platform_accepts_the_built_apk_into_an_install_session() = runBlocking {
        assertTrue("appops did not take effect: $appOpsOutput", installer.canInstall())

        // first() cancels the collection once the status arrives, which is also
        // what abandons the session -- so this leaves nothing behind.
        val status = withTimeout(30_000) { installer.install(apk).first() }

        assertTrue(
            "the platform rejected the APK: $status",
            status is InstallStatus.NeedsConfirmation,
        )
    }

    @Test
    fun a_truncated_apk_is_reported_as_invalid_rather_than_confirmed() = runBlocking {
        // Proves the assertion above is worth something: the same code path with
        // a bad archive does not reach NeedsConfirmation.
        val broken = File(fixture.workDir, "broken.apk")
        broken.writeBytes(apk.readBytes().copyOf(64))

        val status = withTimeout(30_000) { installer.install(broken).first() }

        assertTrue("a truncated APK was accepted: $status", status is InstallStatus.Failed)
        assertTrue(
            "the failure says nothing useful: $status",
            (status as InstallStatus.Failed).message.isNotBlank(),
        )
        // The platform rejected the archive -- not the app being disallowed from
        // installing at all, which would fail this test for the wrong reason and
        // make the test above meaningless too.
        assertEquals("this is a permissions failure, not a bad APK", null, status.settings)
    }

    @Test
    fun the_built_apk_installs_and_launches_as_a_real_package() {
        // The milestone's actual criterion. Done through shell rather than
        // ApkInstaller because that one cannot get past the confirmation dialog
        // without a person; what is being tested here is the APK, not the class.
        // Two hops, and both are forced. The app cannot write /data/local/tmp,
        // and the installer cannot read the app's own storage: /storage is
        // FUSE-backed, and SELinux denies system_server any read of a fuse file
        // -- pm says so and names /data/local/tmp as the way round it. So the
        // app writes where it can, and shell moves it where the installer can.
        val staged = File(
            requireNotNull(fixture.context.getExternalFilesDir(null)) { "no external files dir" },
            "install-me.apk",
        )
        apk.copyTo(staged, overwrite = true)
        val readable = "/data/local/tmp/aide-install-me.apk"
        shell("cp ${staged.absolutePath} $readable")

        val output = shell("pm install -t -r $readable")
        shell("rm -f $readable")
        assertTrue("pm install refused the APK: $output", output.contains("Success"))

        val installed = fixture.context.packageManager
            .getPackageInfo(builtPackage, PackageManager.GET_ACTIVITIES)
        assertEquals(builtPackage, installed.packageName)
        assertEquals(
            "the launcher activity did not survive installation",
            "$builtPackage.MainActivity",
            installed.activities?.singleOrNull()?.name,
        )
    }

    /**
     * Runs [command] as shell and returns everything it printed.
     *
     * `executeShellCommandRwe` rather than `executeShellCommand`: the latter
     * returns stdout only, and `pm install` reports every refusal on stderr --
     * so a failure arrives as an empty string and the assertion that catches it
     * cannot say why.
     *
     * The command is not wrapped in `sh -c`. UiAutomation passes it to
     * `Runtime.exec`, which tokenises on whitespace and does not honour quotes,
     * so the quoted script arrives as a dozen separate arguments.
     */
    private fun shell(command: String): String {
        val streams = instrumentation.uiAutomation.executeShellCommandRwe(command)
        val (stdout, stdin, stderr) = streams
        stdin.close()
        return listOf(stdout, stderr).joinToString("") { descriptor ->
            descriptor.use {
                java.io.FileInputStream(it.fileDescriptor).use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }
        }.trim()
    }

    private operator fun <T> Array<T>.component3(): T = this[2]
}
