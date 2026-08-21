package com.osamu.aide.toolchain.nativetools

/** Outcome of one native tool invocation. */
data class ToolResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /**
     * Diagnostics to show the user. aapt2 reports resource errors on stderr and
     * says nothing on stdout, so stderr is preferred when both are present.
     */
    val diagnostics: String
        get() = when {
            stderr.isNotBlank() -> stderr
            else -> stdout
        }
}
