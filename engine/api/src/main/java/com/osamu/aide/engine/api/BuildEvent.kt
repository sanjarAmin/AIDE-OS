package com.osamu.aide.engine.api

/**
 * Progress, streamed while a build runs.
 *
 * Emitted as they happen rather than collected and returned, because the whole
 * premise of the fast path is that the user is watching: a ten-second build that
 * reports nothing for ten seconds reads as a hang, and a compile error should
 * appear when the compiler finds it, not when the build gives up.
 */
sealed interface BuildEvent {

    data class StageStarted(val stage: BuildStage) : BuildEvent

    data class StageCompleted(val stage: BuildStage, val durationMillis: Long) : BuildEvent

    data class DiagnosticReported(val diagnostic: Diagnostic) : BuildEvent

    /**
     * Something worth putting in the log that is not a stage and not a problem.
     *
     * Resolving dependencies is the case that forced this: a first resolve is a
     * minute of network with nothing to show, and it happens before any stage
     * has started. Reporting it as a diagnostic would file it under Problems,
     * where it is not one.
     */
    data class Note(val message: String) : BuildEvent

    /** Always the last event, for both outcomes. */
    data class Finished(val result: BuildResult) : BuildEvent
}
