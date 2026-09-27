package com.osamu.aide.engine.api

/**
 * How a run ended.
 *
 * **A non-zero exit is [Exited], not [Failed].** A program that ran and
 * returned 1 did what it was asked; `Failed` means it never ran. Conflating
 * them is a mistake this project has made before in the other direction --
 * `NativeToolRunner` returns success for a compiler that rejected its input,
 * and a test asserting otherwise had to be corrected. `tools/mono/FINDINGS.md`.
 */
sealed interface RunResult {

    val durationMillis: Long

    data class Exited(
        val exitCode: Int,
        override val durationMillis: Long,
    ) : RunResult {
        val succeeded: Boolean get() = exitCode == 0
    }

    /** The program could not be started: no runtime, no entry point, no ABI. */
    data class Failed(
        val message: String,
        override val durationMillis: Long,
    ) : RunResult
}
