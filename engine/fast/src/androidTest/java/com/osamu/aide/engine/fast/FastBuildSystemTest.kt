package com.osamu.aide.engine.fast

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.apksig.ApkVerifier
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.awaitResult
import com.osamu.aide.engine.api.hasErrors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * M2: a project goes in, an installable APK comes out.
 *
 * This is the milestone the whole plan is ordered around, so it asserts on the
 * artefact rather than on the pipeline: apksig verifies the signatures and the
 * platform's own package parser reads the manifest. Both of those are checks the
 * device performs at install time, which is the only opinion that counts.
 */
@RunWith(AndroidJUnit4::class)
class FastBuildSystemTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var engine: FastBuildSystem

    @Before
    fun setUp() {
        fixture = EngineTestFixture("fast-build-system-test")
        fixture.assumeAapt2Supported()
        engine = FastBuildSystem(fixture.runner, fixture.platform, DefaultDispatcherProvider())
    }

    private fun request(language: SourceLanguage = SourceLanguage.JAVA) = BuildRequest(
        project = fixture.project(language = language),
        outputDir = File(fixture.workDir, "out"),
    )

    @Test
    fun builds_the_template_into_an_apk_the_platform_accepts() = runTest {
        val request = request()

        val result = engine.build(request).awaitResult()

        assertTrue("build failed: $result", result is BuildResult.Success)
        val apk = (result as BuildResult.Success).apk
        assertTrue("no APK at ${apk.path}", apk.isFile)

        // apksig's verifier, which is the code the platform runs.
        val verification = ApkVerifier.Builder(apk).build().verify()
        assertTrue(
            "signature did not verify: ${verification.errors}",
            verification.isVerified,
        )
        assertTrue("v2 signature missing", verification.isVerifiedUsingV2Scheme)

        // The v1 (JAR) signature is checked by looking for it, not by asking
        // the verifier: at this manifest's minSdk of 26 no device would use v1,
        // so apksig does not verify it and reports isVerifiedUsingV1Scheme as
        // false however present it is. It still has to be written -- a project
        // declaring an older minSdk gets no other signature a device that old
        // understands.
        val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(
            "no v1 (JAR) signature in $entries",
            entries.any { it.startsWith("META-INF/") && it.endsWith(".SF") },
        )

        // And the platform's own package parser -- the same one PackageInstaller
        // uses, so this is the manifest, resource table and zip structure being
        // read by the thing that will refuse the install if any of it is wrong.
        val packageInfo = fixture.context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
        assertTrue("the platform could not parse the APK", packageInfo != null)
        assertEquals("com.example.demo", packageInfo!!.packageName)
        assertEquals(
            "the launcher activity is missing",
            "com.example.demo.MainActivity",
            packageInfo.activities?.singleOrNull()?.name,
        )
    }

    @Test
    fun the_resource_table_is_stored_uncompressed() = runTest {
        // From API 30 the platform maps resources.arsc straight out of the APK
        // and refuses to install a package whose table is deflated. Nothing
        // upstream of packaging would catch it: the APK is well-formed either
        // way, and the failure arrives as a bare install error code.
        val result = engine.build(request()).awaitResult() as BuildResult.Success

        ZipFile(result.apk).use { apk ->
            val table = requireNotNull(apk.getEntry("resources.arsc")) {
                "no resource table in the APK"
            }
            assertEquals(ZipEntry.STORED.toLong(), table.method.toLong())
        }
    }

    @Test
    fun reports_every_stage_in_order_and_finishes_once() = runTest {
        val events = engine.build(request()).toList()

        assertEquals(
            listOf(
                BuildStage.COMPILE_RESOURCES,
                BuildStage.LINK_RESOURCES,
                BuildStage.COMPILE_JAVA,
                BuildStage.DEX,
                BuildStage.PACKAGE,
                BuildStage.SIGN,
            ),
            events.filterIsInstance<BuildEvent.StageStarted>().map { it.stage },
        )
        assertEquals(
            "a stage started and never completed",
            events.filterIsInstance<BuildEvent.StageStarted>().map { it.stage },
            events.filterIsInstance<BuildEvent.StageCompleted>().map { it.stage },
        )
        assertEquals(1, events.filterIsInstance<BuildEvent.Finished>().size)
        assertTrue("Finished was not last", events.last() is BuildEvent.Finished)
    }

    @Test
    fun a_broken_source_fails_at_the_compile_stage_with_a_diagnostic() = runTest {
        val request = request()
        val layout = ProjectLayout.of(request.project)
        val activity = layout.javaSources().single()
        activity.writeText(activity.readText().replace("setContentView(text);", "nope;"))

        val events = engine.build(request).toList()
        val result = (events.last() as BuildEvent.Finished).result

        assertTrue("a broken source should fail the build", result is BuildResult.Failure)
        assertEquals(BuildStage.COMPILE_JAVA, (result as BuildResult.Failure).stage)
        assertTrue("no diagnostics in the result", result.diagnostics.hasErrors)

        // The stage's problems reach the caller while it is running, not only in
        // the final result -- the point of streaming events at all.
        assertTrue(
            "no diagnostic was reported as it happened",
            events.filterIsInstance<BuildEvent.DiagnosticReported>().isNotEmpty(),
        )

        // Stages after the failure did not run.
        assertEquals(
            emptyList<BuildStage>(),
            events.filterIsInstance<BuildEvent.StageStarted>()
                .map { it.stage }
                .filter { it == BuildStage.DEX || it == BuildStage.SIGN },
        )
    }

    @Test
    fun a_kotlin_project_is_refused_rather_than_built_without_its_classes() = runTest {
        val result = engine.build(request(SourceLanguage.KOTLIN)).awaitResult()

        assertTrue(result is BuildResult.Failure)
        assertEquals(null, (result as BuildResult.Failure).stage)
        assertTrue(
            "the message does not say why: ${result.message}",
            result.message.contains("Kotlin"),
        )
    }

    @Test
    fun a_hello_world_builds_inside_the_ten_second_budget() = runTest {
        // docs/PLAN.md makes this an assertion rather than an aspiration: the
        // fast path's whole reason to exist is that the user is watching it.
        // Measured on the second build so it is the steady-state number -- the
        // first pays for loading ECJ and D8, which happens once per process.
        engine.build(request()).awaitResult()

        val result = engine.build(request()).awaitResult()

        assertTrue("build failed: $result", result is BuildResult.Success)
        val millis = result.durationMillis
        assertTrue("hello-world took ${millis}ms, budget is 10000ms", millis < 10_000)
    }
}
