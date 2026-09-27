package com.osamu.aide.engine.api

import java.io.File

/**
 * How a build ended.
 *
 * A failed build is an ordinary outcome, not an error condition -- the common
 * case is a typo -- so this is a result type rather than an exception or an
 * `AppResult.Failure`. Diagnostics are carried by both cases: a build can
 * succeed with warnings, and a build that fails still has everything the tools
 * managed to say before it did.
 */
sealed interface BuildResult {

    val diagnostics: List<Diagnostic>
    val durationMillis: Long

    data class Success(
        val apk: File,
        override val durationMillis: Long,
        override val diagnostics: List<Diagnostic> = emptyList(),
    ) : BuildResult

    data class Failure(
        /** Where it stopped. Null when the build never got as far as a stage. */
        val stage: BuildStage?,
        /** Why, in one line, for when there are no diagnostics to show. */
        val message: String,
        override val durationMillis: Long,
        override val diagnostics: List<Diagnostic> = emptyList(),
    ) : BuildResult

    val succeeded: Boolean get() = this is Success
}
