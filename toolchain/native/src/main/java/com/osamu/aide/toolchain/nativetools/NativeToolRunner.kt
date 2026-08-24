package com.osamu.aide.toolchain.nativetools

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Executes bundled native tools.
 *
 * Runs on the compiler dispatcher rather than IO: invocations are long and
 * CPU-bound, and sharing the IO pool would let a build stall the editor's file
 * reads.
 */
class NativeToolRunner(
    private val toolchain: NativeToolchain,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Runs [tool], handing each line of its output to [onLine] as it is written.
     *
     * Streaming rather than collecting. The reason is not really memory -- a
     * build keeps every diagnostic it is shown anyway -- it is latency: a
     * resource error is worth putting in front of the user the moment aapt2
     * prints it, and a run that only reports on exit cannot do that.
     *
     * [onLine] is called from whichever coroutine drains the stream it came
     * from, but never from both at once (see [drain]), so an implementation may
     * be sequential without being thread-safe. It must not block for long: the
     * pipe it came from is not being read while it runs.
     */
    suspend fun run(
        tool: NativeTool,
        args: List<String>,
        workingDir: File? = null,
        onLine: (ToolLine) -> Unit = {},
    ): AppResult<ToolResult> {
        val executable = when (val availability = toolchain.locate(tool)) {
            is ToolAvailability.Available -> availability.executable
            else -> return AppResult.Failure(AppError(describe(availability)))
        }

        return withContext(dispatchers.compiler) {
            try {
                val process = ProcessBuilder(listOf(executable.absolutePath) + args)
                    .apply { workingDir?.let { directory(it) } }
                    .start()

                // Both pipes must be drained concurrently. aapt2 can emit more
                // output than a pipe buffer holds, and reading them in sequence
                // deadlocks: the process blocks writing to the stream we are
                // not reading yet, so it never exits and waitFor never returns.
                val sink = Any()
                coroutineScope {
                    val out = async { drain(process.inputStream, ToolStream.STDOUT, sink, onLine) }
                    val err = async { drain(process.errorStream, ToolStream.STDERR, sink, onLine) }
                    out.await()
                    err.await()
                }

                AppResult.Success(ToolResult(process.waitFor()))
            } catch (e: Exception) {
                AppResult.Failure(
                    AppError("Could not run ${tool.libraryName}: ${e.message}", e),
                )
            }
        }
    }

    /**
     * Reads one stream to its end, a line at a time.
     *
     * The callback is made under [sink] so that the two concurrent drains take
     * turns. That is a contract, not an optimisation: without it every caller
     * would need a thread-safe collector for a callback that reads as though it
     * were sequential, and the first one to forget would corrupt a list of
     * diagnostics on a device and nowhere else. The lock is held only across the
     * callback, never across a read, so a slow consumer on one stream cannot
     * stop the other pipe from being drained.
     */
    private fun drain(
        stream: InputStream,
        tag: ToolStream,
        sink: Any,
        onLine: (ToolLine) -> Unit,
    ) {
        stream.bufferedReader().forEachLine { line ->
            synchronized(sink) { onLine(ToolLine(tag, line)) }
        }
    }

    private fun describe(availability: ToolAvailability): String = when (availability) {
        is ToolAvailability.Available -> ""
        is ToolAvailability.UnsupportedApiLevel ->
            "${availability.tool.name} needs Android API ${availability.required} " +
                "or newer; this device is API ${availability.actual}."
        is ToolAvailability.Missing ->
            "${availability.tool.name} is not bundled for this device's ABI " +
                "(expected ${availability.expectedAt.absolutePath})."
        is ToolAvailability.NotExecutable ->
            "${availability.tool.name} is present but not executable " +
                "(${availability.path.absolutePath}); the APK may have been " +
                "installed without extracting native libraries."
    }
}
