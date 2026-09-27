package com.osamu.aide.engine.api

import java.io.File

enum class DiagnosticSeverity { ERROR, WARNING, INFO }

/**
 * One message from a build tool, normalised out of whatever that tool prints.
 *
 * The location is what makes a diagnostic useful on a phone: it is the
 * difference between a wall of compiler output and a tappable jump into the
 * editor. Tools that report no location -- a linker, a signing failure -- leave
 * [file] null, and [line] and [column] at [UNKNOWN].
 *
 * [file] should be **relative to the project root**. Whoever builds the
 * diagnostic knows where the root is; nothing downstream does, and by then the
 * choice is between an absolute path full of `/data/user/0/...` and a bare
 * filename that cannot tell `values/strings.xml` from
 * `values-night/strings.xml`.
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val file: File? = null,
    val line: Int = UNKNOWN,
    val column: Int = UNKNOWN,
) {
    val hasLocation: Boolean get() = file != null && line != UNKNOWN

    /** `src/Main.java:12:5: error: cannot find symbol`, for logs and bug reports. */
    fun describe(): String = buildString {
        file?.let {
            append(it.path)
            if (line != UNKNOWN) {
                append(':').append(line)
                if (column != UNKNOWN) append(':').append(column)
            }
            append(": ")
        }
        append(severity.name.lowercase()).append(": ").append(message)
    }

    companion object {
        /** Lines and columns are 1-based, so 0 cannot be a real position. */
        const val UNKNOWN = 0
    }
}

val List<Diagnostic>.errors: List<Diagnostic>
    get() = filter { it.severity == DiagnosticSeverity.ERROR }

val List<Diagnostic>.hasErrors: Boolean
    get() = any { it.severity == DiagnosticSeverity.ERROR }
