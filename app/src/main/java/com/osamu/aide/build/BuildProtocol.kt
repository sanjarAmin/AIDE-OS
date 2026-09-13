package com.osamu.aide.build

import android.os.Bundle
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * What crosses the boundary between the app and the build process.
 *
 * Deliberately **not** `Parcelable` on the domain types. Making `BuildEvent`,
 * `BuildResult`, `Diagnostic` and `Project` parcelable would push an Android
 * dependency and a serialisation contract down into `:engine:api` and
 * `:core:fs`, whose whole value is that they know nothing about the platform —
 * and it would make every future field silently part of a wire format. Here the
 * boundary is one file, and adding a field to a domain type does not change it
 * until someone chooses to send that field.
 *
 * Encoding is by hand and by name. Verbose, and the verbosity is the point: an
 * enum sent as an ordinal is a bug the day someone reorders the enum, and it is
 * a bug with no symptom until a build reports the wrong stage.
 */
object BuildProtocol {

    // -- Project -------------------------------------------------------------

    /**
     * Whether the request is a debug build.
     *
     * Sent beside the project rather than folded into it: a project is not
     * debug or release, a *build* is, and the same project is built both ways
     * from one screen.
     */
    const val KEY_DEBUGGABLE = "build.debuggable"

    fun encodeProject(project: Project): Bundle = Bundle().apply {
        putString(KEY_NAME, project.name)
        putString(KEY_ROOT, project.rootDir.absolutePath)
        putString(KEY_APPLICATION_ID, project.applicationId)
        putString(KEY_LANGUAGE, project.language.name)
        putString(KEY_ENGINE, project.engine.name)
        putLong(KEY_LAST_OPENED, project.lastOpenedAt)
        putStringArrayList(KEY_DEPENDENCIES, ArrayList(project.dependencies))
    }

    fun decodeProject(bundle: Bundle): Project = Project(
        name = bundle.getString(KEY_NAME).orEmpty(),
        rootDir = File(bundle.getString(KEY_ROOT).orEmpty()),
        applicationId = bundle.getString(KEY_APPLICATION_ID).orEmpty(),
        language = enumOrFirst(bundle.getString(KEY_LANGUAGE), SourceLanguage.entries),
        engine = enumOrFirst(bundle.getString(KEY_ENGINE), BuildEngine.entries),
        lastOpenedAt = bundle.getLong(KEY_LAST_OPENED),
        dependencies = bundle.getStringArrayList(KEY_DEPENDENCIES).orEmpty(),
    )

    // -- BuildEvent ----------------------------------------------------------

    fun encodeEvent(event: BuildEvent): Bundle = Bundle().apply {
        when (event) {
            is BuildEvent.StageStarted -> {
                putString(KEY_KIND, KIND_STAGE_STARTED)
                putString(KEY_STAGE, event.stage.name)
            }
            is BuildEvent.StageCompleted -> {
                putString(KEY_KIND, KIND_STAGE_COMPLETED)
                putString(KEY_STAGE, event.stage.name)
                putLong(KEY_DURATION, event.durationMillis)
            }
            is BuildEvent.DiagnosticReported -> {
                putString(KEY_KIND, KIND_DIAGNOSTIC)
                putBundle(KEY_DIAGNOSTIC, encodeDiagnostic(event.diagnostic))
            }
            is BuildEvent.Note -> {
                putString(KEY_KIND, KIND_NOTE)
                putString(KEY_MESSAGE, event.message)
            }
            is BuildEvent.Finished -> {
                putString(KEY_KIND, KIND_FINISHED)
                putBundle(KEY_RESULT, encodeResult(event.result))
            }
        }
    }

    /**
     * Null for anything unrecognised.
     *
     * The two processes are the same APK and cannot disagree about the
     * vocabulary — but they *can* be different versions of it for the moment an
     * update is being applied, and an event nobody understands should be
     * dropped rather than crash the build that is already running.
     */
    fun decodeEvent(bundle: Bundle): BuildEvent? = when (bundle.getString(KEY_KIND)) {
        KIND_STAGE_STARTED ->
            stage(bundle.getString(KEY_STAGE))?.let { BuildEvent.StageStarted(it) }
        KIND_STAGE_COMPLETED ->
            stage(bundle.getString(KEY_STAGE))?.let {
                BuildEvent.StageCompleted(it, bundle.getLong(KEY_DURATION))
            }
        KIND_DIAGNOSTIC ->
            bundle.getBundle(KEY_DIAGNOSTIC)?.let { BuildEvent.DiagnosticReported(decodeDiagnostic(it)) }
        KIND_NOTE -> BuildEvent.Note(bundle.getString(KEY_MESSAGE).orEmpty())
        KIND_FINISHED ->
            bundle.getBundle(KEY_RESULT)?.let { BuildEvent.Finished(decodeResult(it)) }
        else -> null
    }

    // -- BuildResult ---------------------------------------------------------

    private fun encodeResult(result: BuildResult): Bundle = Bundle().apply {
        putLong(KEY_DURATION, result.durationMillis)
        putParcelableArrayList(
            KEY_DIAGNOSTICS,
            ArrayList(result.diagnostics.map(::encodeDiagnostic)),
        )
        when (result) {
            is BuildResult.Success -> {
                putString(KEY_KIND, KIND_SUCCESS)
                putString(KEY_APK, result.apk.absolutePath)
            }
            is BuildResult.Failure -> {
                putString(KEY_KIND, KIND_FAILURE)
                putString(KEY_MESSAGE, result.message)
                // Absent rather than a sentinel: a failure before any stage
                // began genuinely has no stage, and "" is not a BuildStage.
                result.stage?.let { putString(KEY_STAGE, it.name) }
            }
        }
    }

    private fun decodeResult(bundle: Bundle): BuildResult {
        val diagnostics = bundle.parcelableBundles(KEY_DIAGNOSTICS).map(::decodeDiagnostic)
        val duration = bundle.getLong(KEY_DURATION)
        return if (bundle.getString(KEY_KIND) == KIND_SUCCESS) {
            BuildResult.Success(File(bundle.getString(KEY_APK).orEmpty()), duration, diagnostics)
        } else {
            BuildResult.Failure(
                stage = stage(bundle.getString(KEY_STAGE)),
                message = bundle.getString(KEY_MESSAGE).orEmpty(),
                durationMillis = duration,
                diagnostics = diagnostics,
            )
        }
    }

    // -- Diagnostic ----------------------------------------------------------

    private fun encodeDiagnostic(diagnostic: Diagnostic): Bundle = Bundle().apply {
        putString(KEY_SEVERITY, diagnostic.severity.name)
        putString(KEY_MESSAGE, diagnostic.message)
        // A diagnostic with no location is the ordinary case for a linker or a
        // signing failure, and its path must stay null rather than becoming "".
        diagnostic.file?.let { putString(KEY_FILE, it.path) }
        putInt(KEY_LINE, diagnostic.line)
        putInt(KEY_COLUMN, diagnostic.column)
    }

    private fun decodeDiagnostic(bundle: Bundle): Diagnostic = Diagnostic(
        severity = enumOrFirst(bundle.getString(KEY_SEVERITY), DiagnosticSeverity.entries),
        message = bundle.getString(KEY_MESSAGE).orEmpty(),
        file = bundle.getString(KEY_FILE)?.let(::File),
        line = bundle.getInt(KEY_LINE, Diagnostic.UNKNOWN),
        column = bundle.getInt(KEY_COLUMN, Diagnostic.UNKNOWN),
    )

    // -- helpers -------------------------------------------------------------

    private fun stage(name: String?): BuildStage? =
        BuildStage.entries.firstOrNull { it.name == name }

    private fun <T : Enum<T>> enumOrFirst(name: String?, values: List<T>): T =
        values.firstOrNull { it.name == name } ?: values.first()

    @Suppress("DEPRECATION")
    private fun Bundle.parcelableBundles(key: String): List<Bundle> =
        getParcelableArrayList<Bundle>(key).orEmpty()

    const val MSG_BUILD = 1
    const val MSG_EVENT = 2
    const val MSG_CANCEL = 3

    const val KEY_PROJECT = "project"
    const val KEY_EVENT = "event"

    private const val KEY_KIND = "kind"
    private const val KEY_NAME = "name"
    private const val KEY_ROOT = "root"
    private const val KEY_APPLICATION_ID = "applicationId"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ENGINE = "engine"
    private const val KEY_LAST_OPENED = "lastOpenedAt"
    private const val KEY_DEPENDENCIES = "dependencies"
    private const val KEY_STAGE = "stage"
    private const val KEY_DURATION = "duration"
    private const val KEY_MESSAGE = "message"
    private const val KEY_DIAGNOSTIC = "diagnostic"
    private const val KEY_DIAGNOSTICS = "diagnostics"
    private const val KEY_RESULT = "result"
    private const val KEY_APK = "apk"
    private const val KEY_SEVERITY = "severity"
    private const val KEY_FILE = "file"
    private const val KEY_LINE = "line"
    private const val KEY_COLUMN = "column"

    private const val KIND_STAGE_STARTED = "stageStarted"
    private const val KIND_STAGE_COMPLETED = "stageCompleted"
    private const val KIND_DIAGNOSTIC = "diagnosticReported"
    private const val KIND_NOTE = "note"
    private const val KIND_FINISHED = "finished"
    private const val KIND_SUCCESS = "success"
    private const val KIND_FAILURE = "failure"
}
