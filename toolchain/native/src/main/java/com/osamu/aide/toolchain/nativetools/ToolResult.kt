package com.osamu.aide.toolchain.nativetools

/** Which of a process's two output streams a line arrived on. */
enum class ToolStream { STDOUT, STDERR }

/**
 * One line of a tool's output, tagged with the stream that carried it.
 *
 * The tag is kept because tools disagree about which stream means what. aapt2
 * routes everything through its own diagnostics printer -- errors, warnings and
 * the output of `version` alike -- so it writes to stderr and leaves stdout
 * empty, and a caller that assumed the usual split would read nothing at all.
 */
data class ToolLine(val stream: ToolStream, val text: String)

/**
 * Outcome of one native tool invocation.
 *
 * Deliberately carries no output. Lines are handed to the caller as the process
 * writes them (see [NativeToolRunner.run]), so that a build can report a broken
 * resource while aapt2 is still working rather than only once it has exited,
 * and so that nothing here has to hold a whole run's output to return at the
 * end. A caller that wants it whole is free to accumulate it; the ones that
 * only want to forward each line no longer pay for a copy.
 */
data class ToolResult(val exitCode: Int) {
    val isSuccess: Boolean get() = exitCode == 0
}
