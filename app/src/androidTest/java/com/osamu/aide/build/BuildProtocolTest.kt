package com.osamu.aide.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What survives the trip to the build process.
 *
 * A wire format is only ever wrong at runtime, and the failures it produces are
 * the quiet kind this project keeps meeting: a dropped field is not a crash,
 * it is a diagnostic that lost its line number, or a build reporting the wrong
 * stage. Every case is round-tripped rather than spot-checked.
 */
@RunWith(AndroidJUnit4::class)
class BuildProtocolTest {

    private fun roundTrip(event: BuildEvent): BuildEvent? =
        BuildProtocol.decodeEvent(BuildProtocol.encodeEvent(event))

    @Test
    fun a_project_survives_the_trip() {
        val project = Project(
            name = "demo",
            rootDir = File("/data/user/0/com.osamu.aide/files/projects/demo"),
            applicationId = "com.example.demo",
            language = SourceLanguage.KOTLIN,
            engine = BuildEngine.GRADLE,
            lastOpenedAt = 1_725_000_000_000L,
            dependencies = listOf("androidx.core:core-ktx:1.13.1", "com.google.guava:guava:33.0.0-android"),
        )

        assertEquals(project, BuildProtocol.decodeProject(BuildProtocol.encodeProject(project)))
    }

    @Test
    fun every_kind_of_event_survives_the_trip() {
        val events = listOf(
            BuildEvent.StageStarted(BuildStage.DEX),
            BuildEvent.StageCompleted(BuildStage.LINK_RESOURCES, 1234L),
            BuildEvent.Note("Resolving dependencies."),
            BuildEvent.DiagnosticReported(
                Diagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    message = "cannot find symbol",
                    file = File("src/main/java/demo/Main.java"),
                    line = 12,
                    column = 5,
                ),
            ),
        )

        for (event in events) {
            assertEquals("$event did not survive", event, roundTrip(event))
        }
    }

    @Test
    fun a_successful_result_survives_with_its_diagnostics() {
        val event = BuildEvent.Finished(
            BuildResult.Success(
                apk = File("/data/user/0/com.osamu.aide/files/out/app-debug.apk"),
                durationMillis = 2760L,
                diagnostics = listOf(
                    Diagnostic(DiagnosticSeverity.WARNING, "deprecated", File("src/A.java"), 3, 1),
                ),
            ),
        )

        assertEquals(event, roundTrip(event))
    }

    /**
     * A failure before any stage began has no stage, and null is not "".
     *
     * Encoded as an absent key rather than a sentinel, because every string
     * that is not a `BuildStage` name would decode to the same thing and the
     * UI would attribute the failure to whichever stage sorted first.
     */
    @Test
    fun a_failure_with_no_stage_keeps_its_absence() {
        val event = BuildEvent.Finished(
            BuildResult.Failure(stage = null, message = "No Android SDK is installed.", durationMillis = 40L),
        )

        val decoded = roundTrip(event) as BuildEvent.Finished
        assertNull((decoded.result as BuildResult.Failure).stage)
        assertEquals(event, decoded)
    }

    @Test
    fun a_failure_with_a_stage_keeps_it() {
        val event = BuildEvent.Finished(
            BuildResult.Failure(
                stage = BuildStage.COMPILE_JAVA,
                message = "compilation failed",
                durationMillis = 900L,
                diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, "boom")),
            ),
        )

        assertEquals(event, roundTrip(event))
    }

    /**
     * A diagnostic with no location keeps none.
     *
     * The ordinary case for a linker or a signing failure. Encoding a null path
     * as `""` would make `hasLocation` true and give the editor a file to open
     * that is not a file.
     */
    @Test
    fun a_diagnostic_without_a_location_does_not_gain_one() {
        val event = BuildEvent.DiagnosticReported(
            Diagnostic(DiagnosticSeverity.ERROR, "failed to sign the APK"),
        )

        val decoded = roundTrip(event) as BuildEvent.DiagnosticReported
        assertNull(decoded.diagnostic.file)
        assertEquals(Diagnostic.UNKNOWN, decoded.diagnostic.line)
        assertEquals(event, decoded)
    }

    /**
     * Enums travel by name.
     *
     * An ordinal is a bug the day someone reorders `BuildStage`, and it is a
     * bug with no symptom until a build reports the wrong stage — so this
     * asserts the encoded form, not just that a round trip works.
     */
    @Test
    fun stages_are_encoded_by_name_rather_than_ordinal() {
        val encoded = BuildProtocol.encodeEvent(BuildEvent.StageStarted(BuildStage.DEX))

        assertEquals("DEX", encoded.getString("stage"))
    }

    /** An event from a vocabulary this process does not know is dropped, not fatal. */
    @Test
    fun an_unrecognised_event_decodes_to_null() {
        val encoded = BuildProtocol.encodeEvent(BuildEvent.Note("x"))
        encoded.putString("kind", "somethingFromALaterVersion")

        assertNull(BuildProtocol.decodeEvent(encoded))
    }
}
