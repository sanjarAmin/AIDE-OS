package com.osamu.aide.build

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * That builds really do run somewhere else.
 *
 * R3 is a memory risk, and the mitigation is only worth anything if the
 * compiler's heap is genuinely not the UI process's heap. That is not something
 * a unit test can assert and not something the code can be read for — a
 * `android:process` attribute is a manifest claim, and manifests get merged.
 * So this asks the platform.
 */
@RunWith(AndroidJUnit4::class)
class BuildProcessTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun project() = Project(
        name = "process-probe",
        // Deliberately not a real project: this is about *where* the build ran,
        // and a build that fails for want of sources still ran somewhere.
        rootDir = File(context.cacheDir, "no-such-project"),
        applicationId = "com.example.probe",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    /**
     * The service is declared in its own process.
     *
     * Read from the merged manifest rather than the source one: `:build` in a
     * library's manifest, or a merge that dropped the attribute, would leave
     * the service in the app's process and nothing else would notice.
     */
    @Test
    fun the_build_service_is_declared_in_its_own_process() {
        val services = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES)
            .services
            .orEmpty()

        val service = services.firstOrNull { it.name == BuildService::class.java.name }
        assertTrue("BuildService is not in the merged manifest", service != null)
        assertNotEquals(
            "BuildService shares the UI process, so its heap is the editor's heap",
            context.packageName,
            service!!.processName,
        )
        assertTrue(
            "unexpected process name: ${service.processName}",
            BuildService.isBuildProcess(service.processName),
        )
    }

    /**
     * Running a build actually starts that process.
     *
     * The declaration above is necessary and not sufficient: a client that
     * never binds, or binds without `BIND_AUTO_CREATE`, leaves the process
     * unstarted and the build running here after all.
     */
    @Test
    fun building_starts_the_build_process() {
        val runner = RemoteBuildRunner(context)

        val events = runBlocking {
            withTimeout(BUILD_TIMEOUT_MILLIS) { runner.build(project(), debuggable = true, debugger = null).toList() }
        }

        // Whatever the outcome, the contract is that a build ends with
        // Finished -- including the failure a project with no sources earns.
        assertTrue("no events arrived from the build process", events.isNotEmpty())
        assertTrue("the build never finished", events.last() is BuildEvent.Finished)

        val running = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses
            .orEmpty()
            .map { it.processName }
        assertTrue(
            "the build process was never started; saw $running",
            running.any { BuildService.isBuildProcess(it) },
        )
    }

    private companion object {
        /**
         * Generous: this may be the first bind, which starts a process and
         * builds a Koin graph in it. Short enough that a hang is still a
         * failure rather than a suite that never ends.
         */
        const val BUILD_TIMEOUT_MILLIS = 120_000L
    }
}
