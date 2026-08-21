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

    /** Always the last event, for both outcomes. */
    data class Finished(val result: BuildResult) : BuildEvent
}
