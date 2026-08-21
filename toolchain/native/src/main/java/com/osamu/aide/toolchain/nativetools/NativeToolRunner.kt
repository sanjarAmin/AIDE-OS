package com.osamu.aide.toolchain.nativetools

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

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

    suspend fun run(
        tool: NativeTool,
        args: List<String>,
        workingDir: File? = null,
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
                val (stdout, stderr) = coroutineScope {
                    val out = async { process.inputStream.bufferedReader().readText() }
                    val err = async { process.errorStream.bufferedReader().readText() }
                    out.await() to err.await()
                }

                val exitCode = process.waitFor()
                AppResult.Success(ToolResult(exitCode, stdout.trim(), stderr.trim()))
            } catch (e: Exception) {
                AppResult.Failure(
                    AppError("Could not run ${tool.libraryName}: ${e.message}", e),
                )
            }
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
