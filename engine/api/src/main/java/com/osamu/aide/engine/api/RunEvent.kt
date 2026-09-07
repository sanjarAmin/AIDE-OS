package com.osamu.aide.engine.api

/**
 * Progress, streamed while a program runs.
 *
 * Output arrives as the process writes it, for a stronger reason than a build's:
 * a build's log is a record, and a program's output is the program. A run that
 * buffered until exit would be useless for anything long-lived, and wrong for
 * anything interactive.
 */
sealed interface RunEvent {

    /** The process started. Carries what was actually executed, for the log. */
    data class Started(val commandLine: String) : RunEvent

    /**
     * One line, tagged with the stream that carried it.
     *
     * The tag is kept for the reason `ToolLine` keeps it: programs disagree
     * about which stream means what, and a caller that assumed the usual split
     * would colour ordinary output as errors.
     */
    data class Output(val line: String, val stream: RunStream) : RunEvent

    /** Always the last event, for every outcome including a failure to start. */
    data class Finished(val result: RunResult) : RunEvent
}

enum class RunStream { STDOUT, STDERR }
