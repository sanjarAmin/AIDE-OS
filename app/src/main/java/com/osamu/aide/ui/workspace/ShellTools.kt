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
import android.system.Os
import android.system.OsConstants
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

/** Toybox's. Where it is missing the shell starts as before, and only the shell can be ended. */
private const val SETSID = "/system/bin/setsid"

internal suspend fun executeShellCommand(
    workingDir: File,
    command: String,
    timeoutMs: Long = TIMEOUT_MS,
): ProjectFiles.Outcome = withContext(Dispatchers.IO) {
    val pidFile = File.createTempFile("run-shell", ".pid")
    try {
        // **In a session of its own, so it can be ended whole.** Ending only
        // `sh` left whatever it had started -- `npm start`, an `http.server` --
        // running and holding the output pipes open, so the readers below
        // never saw end-of-file, the timeout's own wait never returned, and the
        // assistant's turn hung with Stop unable to finish it. setsid makes the
        // shell a process-group leader; it writes its pid, and the whole group
        // goes with one signal.
        val session = if (File(SETSID).canExecute()) listOf(SETSID) else emptyList()
        val process = ProcessBuilder(
            session + listOf(
                "/system/bin/sh",
                "-c",
                "echo \$\$ > '${pidFile.path.replace("'", "'\\''")}'; $command",
            ),
        )
            .directory(workingDir)
            .apply {
                environment()["PWD"] = workingDir.absolutePath
            }
            .start()
        val endAll = { endSession(pidFile, process, grouped = session.isNotEmpty()) }

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
                    endAll()
                    throw e
                }
            }
        }

        if (outcome == null) {
            endAll()
            ProjectFiles.Outcome.Refused("Command timed out after ${timeoutMs / 1000} seconds.")
        } else {
            ProjectFiles.Outcome.Ok(outcome)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProjectFiles.Outcome.Refused("Could not run shell command: ${e.message}")
    } finally {
        pidFile.delete()
    }
}

/**
 * Kills the command's whole process group, or at least its shell.
 *
 * A negative pid to kill(2) names a group; `android.os.Process.sendSignal`
 * drops anything not positive, so this is `Os.kill`. Only when setsid made the
 * shell a leader, and never this app's own pid, which would be the app's own
 * group: a pid file the shell never wrote falls back to ending the shell alone.
 * The group outlives its leader, so `server &` -- the shell long gone, its
 * child still writing -- is ended too.
 */
private fun endSession(pidFile: File, process: Process, grouped: Boolean) {
    val leader = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
    if (grouped && leader != null && leader > 1 && leader != android.os.Process.myPid()) {
        runCatching { Os.kill(-leader, OsConstants.SIGKILL) }
    }
    process.destroyForcibly()
}

/** What [readStreamBounded] read, and whether it stopped short. */
private class Bounded(val text: String, val truncated: Boolean)

/**
 * Up to [maxChars] of [stream], after which it is closed.
 *
 * Closed rather than drained: a command whose output never ends -- `logcat`,
 * `yes` -- would otherwise run to the timeout and come back with nothing.
 * The cost is that its next write is a SIGPIPE; see [formatCommandResult].
 */
private fun readStreamBounded(stream: InputStream, maxChars: Int): Bounded {
    val builder = StringBuilder()
    var truncated = false
    try {
        stream.bufferedReader().use { reader ->
            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                if (builder.length + read > maxChars) {
                    val remaining = maxChars - builder.length
                    if (remaining > 0) builder.append(buffer, 0, remaining)
                    builder.append("\n... truncated (exceeded ${maxChars / 1024} kB)")
                    truncated = true
                    break
                }
                builder.append(buffer, 0, read)
            }
        }
    } catch (_: Exception) {
        // Stream closed or broken pipe
    }
    return Bounded(builder.toString().trim(), truncated)
}

/** 128 + SIGPIPE: what a command exits with when it writes to a closed pipe. */
private const val SIGPIPE_EXIT = 141

private fun formatCommandResult(exitCode: Int, stdout: Bounded, stderr: Bounded): String {
    // **A pipe this closed is not the command failing.** Output past the limit
    // closes the pipe, the command's next write kills it, and it exits 141 --
    // which read as "Command failed" after a `find` that had worked and merely
    // said too much.
    val cutShort = exitCode == SIGPIPE_EXIT && (stdout.truncated || stderr.truncated)
    return formatCommandResult(if (cutShort) 0 else exitCode, stdout.text, stderr.text) +
        if (cutShort) "\n\n(Stopped once its output passed the limit; it had not failed.)" else ""
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
