package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic

/**
 * What one pipeline stage produced.
 *
 * Diagnostics are carried separately from success or failure because the two are
 * independent: a stage can succeed with warnings, and a stage that fails has
 * usually said something useful on its way down. [failure] is a single line for
 * when it has not -- a tool that dies without explaining itself still needs to
 * produce something the user can read.
 */
internal data class StageResult<out T>(
    val value: T?,
    val diagnostics: List<Diagnostic> = emptyList(),
    val failure: String? = null,
) {
    val succeeded: Boolean get() = failure == null

    companion object {
        fun <T> ok(value: T, diagnostics: List<Diagnostic> = emptyList()) =
            StageResult(value, diagnostics)

        fun failed(message: String, diagnostics: List<Diagnostic> = emptyList()) =
            StageResult<Nothing>(null, diagnostics, message)
    }
}
