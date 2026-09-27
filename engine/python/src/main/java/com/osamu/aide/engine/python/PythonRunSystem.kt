package com.osamu.aide.engine.python

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.RunEvent
import com.osamu.aide.engine.api.RunRequest
import com.osamu.aide.engine.api.RunResult
import com.osamu.aide.engine.api.RunStream
import com.osamu.aide.engine.api.RunSystem
import com.osamu.aide.toolchain.nativetools.LaunchPlan
import com.osamu.aide.toolchain.nativetools.PythonToolchain
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * Runs a Python project.
 *
 * All of the awkwardness of starting CPython on Android lives in
 * [PythonToolchain] and none of it is repeated here: this decides *what* to run
 * and turns a process into a flow. `tools/python/FINDINGS.md`.
 *
 * Structurally the twin of `NodeRunSystem`, and deliberately a separate class
 * rather than a parameterisation of it. What the two share is the drain loop
 * and the process lifecycle, which is thirty lines; what differs is the
 * toolchain, the entry point, the package manager and every message the user
 * reads. A common base would be a class whose every method took the differences
 * as arguments.
 *
 * @param home a directory the program may treat as `$HOME`; pip writes a cache
 *   there and defaults to a path this app cannot use.
 * @param cache scratch space, handed on as `TMPDIR`.
 * @param packages where `pip install --target` puts this project's
 *   dependencies, and what a run puts on `PYTHONPATH`. See [pip].
 */
class PythonRunSystem(
    private val python: PythonToolchain,
    private val dispatchers: DispatcherProvider,
    private val home: File,
    private val cache: File,
    private val packages: File,
) : RunSystem {

    /**
     * True when there is an interpreter **and** something to run.
     *
     * Both halves matter: a missing entry point answered by attempting the run
     * produces `can't open file '...': [Errno 2] No such file or directory`,
     * which names a path the user may never have chosen.
     */
    override fun handles(request: RunRequest): Boolean =
        python.isInstalled && request.entryPoint.isFile

    override fun run(request: RunRequest): Flow<RunEvent> = callbackFlow {
        val started = System.currentTimeMillis()

        if (!python.isInstalled) {
            trySend(RunEvent.Finished(failed("Python is not installed.", started)))
            close()
            return@callbackFlow
        }
        if (!request.entryPoint.isFile) {
            trySend(
                RunEvent.Finished(
                    failed("No entry point at ${request.entryPoint.absolutePath}.", started),
                ),
            )
            close()
            return@callbackFlow
        }

        spawn(
            plan = python.planScript(
                script = request.entryPoint,
                arguments = request.arguments,
                environment = python.prepareEnvironment(home, cache, packages) + request.environment,
            ),
            directory = request.projectDir,
            started = started,
            whenItCannotStart = "Python could not start.",
        )
    }.flowOn(dispatchers.io)

    /**
     * Runs pip in [projectDir], streaming its output the way a run streams a
     * program's.
     *
     * **Not part of [RunSystem]**, for the reason `NodeRunSystem.npm` is not:
     * installing dependencies is not running the project, and a `RunSystem`
     * with an `install` on it would be a contract with one implementation's
     * package manager in it.
     *
     * **`--target`, not a virtualenv.** A venv would give the same per-project
     * isolation and costs more than it looks: `python -m venv` bootstraps pip
     * by **spawning the new interpreter**, which in this app's process is an
     * `execve` out of app-private storage and is refused. (The archive cannot
     * do it at all besides -- the bundled wheel lives in a Termux package the
     * closure does not carry, so creation fails at `ensurepip` even where exec
     * is allowed.) Installing into a plain directory that the run puts on
     * `PYTHONPATH` is the same isolation with no second interpreter and nothing
     * to execute. `tools/python/FINDINGS.md` §4.
     */
    fun pip(arguments: List<String>, projectDir: File): Flow<RunEvent> = callbackFlow {
        val started = System.currentTimeMillis()

        if (!python.isInstalled) {
            trySend(RunEvent.Finished(failed("Python is not installed.", started)))
            close()
            return@callbackFlow
        }
        // `--target` is added here rather than left to the caller, because it
        // is the decision this class made: a caller passing `install requests`
        // without it would install into the *toolchain*, where the next
        // component update erases it and every other project sees it meanwhile.
        //
        // **Only on `install`.** It is an option of that subcommand and not of
        // pip, so appending it unconditionally makes `pip --version` exit 2
        // with `no such option: --target` -- which reads as a broken install
        // rather than as this line. Measured, in `PythonRunSystemTest`.
        val plan = python.planPip(
            arguments = if (arguments.firstOrNull() == "install") {
                arguments + listOf("--target", packages.absolutePath)
            } else {
                arguments
            },
            environment = python.prepareEnvironment(home, cache, packages),
        )
        if (plan == null) {
            trySend(RunEvent.Finished(failed("This Python has no pip.", started)))
            close()
            return@callbackFlow
        }

        spawn(
            plan = plan,
            directory = projectDir,
            started = started,
            whenItCannotStart = "pip could not start.",
        )
    }.flowOn(dispatchers.io)

    /**
     * Starts one process and turns it into the rest of the flow.
     *
     * Ends with `awaitClose`, so it must be the last thing its caller does.
     */
    private suspend fun ProducerScope<RunEvent>.spawn(
        plan: LaunchPlan,
        directory: File,
        started: Long,
        whenItCannotStart: String,
    ) {
        val process = try {
            ProcessBuilder(plan.command)
                .directory(directory)
                .apply { environment().putAll(plan.environment) }
                .start()
        } catch (failure: Exception) {
            trySend(RunEvent.Finished(failed(failure.message ?: whenItCannotStart, started)))
            close()
            return
        }

        // The command, not the plan: a reader looking at the log needs to see
        // that it went through the linker.
        trySend(RunEvent.Started(plan.command.joinToString(" ")))

        // **Both streams, concurrently.** A process whose stderr fills while
        // only stdout is read blocks in the kernel and never exits. Python
        // makes this more likely than most: a traceback is stderr, and a
        // program that fails after printing a lot to stdout would deadlock
        // exactly when the user most wants to see why.
        drain(process.inputStream, RunStream.STDOUT)
        drain(process.errorStream, RunStream.STDERR)

        launch(dispatchers.io) {
            val exit = runCatching { process.waitFor() }.getOrDefault(-1)
            trySend(
                RunEvent.Finished(
                    RunResult.Exited(exit, System.currentTimeMillis() - started),
                ),
            )
            close()
        }

        // **Cancelling the collection kills the program.** Without this a
        // stopped run leaves a process holding whatever it opened, and the next
        // run of a server fails on a port that nothing visible is using.
        awaitClose { process.destroyForcibly() }
    }

    /**
     * Forwards one stream until it ends **or the process is killed under it**.
     *
     * A blocking read is not cancellable, so stopping a run destroys the
     * process and the read then fails where it stands -- the ordinary end of
     * draining a cancelled run, not a defect, and letting it escape crashes the
     * collector that just asked the run to stop.
     */
    private fun ProducerScope<RunEvent>.drain(stream: InputStream, tag: RunStream) {
        launch(dispatchers.io) {
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { trySend(RunEvent.Output(it, tag)) }
                }
            }
        }
    }

    private fun failed(message: String, started: Long) =
        RunResult.Failed(message, System.currentTimeMillis() - started)
}
