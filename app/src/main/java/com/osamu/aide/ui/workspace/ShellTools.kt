package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.AideTool
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shell command execution tools offered to the AI assistant.
 *
 * **`run_shell` is [ToolRisk.MUTATING]**: Arbitrary shell commands can alter,
 * delete, or create files in the user's workspace. Following AIDE-OS's core
 * security model, the assistant engine refuses to execute shell commands until
 * the user confirms via the approval prompt.
 */
fun shellTools(
    project: () -> Project?,
    commandRunner: (suspend (File, String) -> ProjectFiles.Outcome)? = null,
): List<AideTool> = listOf(
    AideTool(
        name = "run_shell",
        description =
            "Execute a shell command inside the project root directory and capture stdout, " +
                "stderr, and exit status. Use this for running custom scripts, checking directory " +
                "structures, running build utilities, or inspecting the environment. " +
                "Because shell commands can alter project files or system state, the user must " +
                "confirm execution before it runs.",
        risk = ToolRisk.MUTATING,
        parameters = mapOf(
            "command" to AideTool.Parameter(
                "string",
                "The shell command line to execute (e.g. 'ls -la', 'find . -name *.xml').",
            ),
        ),
        required = listOf("command"),
        handler = { input ->
            val command = input["command"]?.trim()
            if (command.isNullOrEmpty()) {
                return@AideTool ProjectFiles.Outcome.Refused("run_shell needs a command.")
            }
            val target = project()
                ?: return@AideTool ProjectFiles.Outcome.Refused(
                    "No project is open, so there is nowhere to run shell commands.",
                )

            if (commandRunner != null) {
                commandRunner(target.rootDir, command)
            } else {
                executeShellCommand(target.rootDir, command)
            }
        },
    ),
)

private const val TIMEOUT_MS = 30_000L
private const val MAX_OUTPUT_CHARS = 16 * 1024

internal suspend fun executeShellCommand(
    workingDir: File,
    command: String,
    timeoutMs: Long = TIMEOUT_MS,
): ProjectFiles.Outcome = withContext(Dispatchers.IO) {
    try {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workingDir)
            .apply {
                environment()["PWD"] = workingDir.absolutePath
            }
            .start()

        val outcome = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val outAsync = async { readStreamBounded(process.inputStream, MAX_OUTPUT_CHARS) }
                val errAsync = async { readStreamBounded(process.errorStream, MAX_OUTPUT_CHARS) }

                try {
                    val out = outAsync.await()
                    val err = errAsync.await()
                    val exitCode = process.waitFor()

                    formatCommandResult(exitCode, out, err)
                } catch (e: CancellationException) {
                    process.destroyForcibly()
                    throw e
                }
            }
        }

        if (outcome == null) {
            process.destroyForcibly()
            ProjectFiles.Outcome.Refused("Command timed out after ${timeoutMs / 1000} seconds.")
        } else {
            ProjectFiles.Outcome.Ok(outcome)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProjectFiles.Outcome.Refused("Could not run shell command: ${e.message}")
    }
}

private fun readStreamBounded(stream: InputStream, maxChars: Int): String {
    val builder = StringBuilder()
    try {
        stream.bufferedReader().use { reader ->
            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                if (builder.length + read > maxChars) {
                    val remaining = maxChars - builder.length
                    if (remaining > 0) builder.append(buffer, 0, remaining)
                    builder.append("\n... truncated (exceeded ${maxChars / 1024} kB)")
                    break
                }
                builder.append(buffer, 0, read)
            }
        }
    } catch (_: Exception) {
        // Stream closed or broken pipe
    }
    return builder.toString().trim()
}

private fun formatCommandResult(exitCode: Int, stdout: String, stderr: String): String = buildString {
    if (exitCode == 0) {
        if (stdout.isEmpty() && stderr.isEmpty()) {
            append("Command finished successfully with no output (exit code 0).")
        } else {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (stdout.isNotEmpty()) append("\n")
                append("[stderr]:\n").append(stderr)
            }
        }
    } else {
        append("Command failed with exit code ").append(exitCode).append(":\n")
        if (stderr.isNotEmpty()) append(stderr)
        if (stdout.isNotEmpty()) {
            if (stderr.isNotEmpty()) append("\n\n[stdout]:\n")
            append(stdout)
        }
    }
}.trim()
