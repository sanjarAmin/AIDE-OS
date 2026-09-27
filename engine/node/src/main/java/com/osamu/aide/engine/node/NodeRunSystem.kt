package com.osamu.aide.engine.node

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.RunEvent
import com.osamu.aide.engine.api.RunRequest
import com.osamu.aide.engine.api.RunResult
import com.osamu.aide.engine.api.RunStream
import com.osamu.aide.engine.api.RunSystem
import com.osamu.aide.toolchain.nativetools.LaunchPlan
import com.osamu.aide.toolchain.nativetools.NodeToolchain
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * Runs a Node project.
 *
 * All of the awkwardness of starting Node on Android lives in [NodeToolchain]
 * and none of it is repeated here: this decides *what* to run and turns a
 * process into a flow. `tools/node/FINDINGS.md`.
 *
 * @param home a directory the program may treat as `$HOME`; npm writes a cache
 *   there and defaults to a path this app cannot use.
 * @param cache scratch space, handed on as `TMPDIR`.
 */
class NodeRunSystem(
    private val node: NodeToolchain,
    private val dispatchers: DispatcherProvider,
    private val home: File,
    private val cache: File,
) : RunSystem {

    /**
     * True when there is a runtime **and** something to run.
     *
     * Both halves matter: a missing entry point answered by attempting the run
     * produces `Cannot find module`, which names a path the user may never have
     * chosen -- `package.json`'s `main` can point anywhere.
     */
    override fun handles(request: RunRequest): Boolean =
        node.isInstalled && request.entryPoint.isFile

    override fun run(request: RunRequest): Flow<RunEvent> = callbackFlow {
        val started = System.currentTimeMillis()

        if (!node.isInstalled) {
            trySend(RunEvent.Finished(failed("Node is not installed.", started)))
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
            plan = node.planScript(
                script = request.entryPoint,
                arguments = request.arguments,
                environment = node.prepareEnvironment(home, cache) + request.environment,
            ),
            directory = request.projectDir,
            started = started,
            whenItCannotStart = "Node could not start.",
        )
    }.flowOn(dispatchers.io)

    /**
     * Runs npm in [projectDir], streaming its output the way a run streams a
     * program's.
     *
     * **Not part of [RunSystem].** Installing dependencies is not running the
     * project, and the contract describes the second; a `RunSystem` with an
     * `install` on it would be a contract with one implementation's package
     * manager in it. It is here rather than in a class of its own because
     * everything it needs -- the linker plan, `HOME`, the npm cache, the drain
     * loop -- already exists here for the run.
     *
     * npm is invoked as `npm-cli.js` and not through `bin/npm`, which is a
     * shell script that execs `node` off `PATH`: that cannot work when the
     * runtime can only be started through the linker. `tools/node/FINDINGS.md`
     * §3.
     */
    fun npm(projectDir: File, arguments: List<String>): Flow<RunEvent> = callbackFlow {
        val started = System.currentTimeMillis()

        if (!node.isInstalled) {
            trySend(RunEvent.Finished(failed("Node is not installed.", started)))
            close()
            return@callbackFlow
        }
        val plan = node.planNpm(
            arguments = arguments,
            environment = node.prepareEnvironment(home, cache),
        )
        if (plan == null) {
            // A real shape, not a hypothetical: Termux ships the runtime and
            // npm as separate packages, so an archive assembled from the
            // runtime's closure alone has no npm in it at all.
            trySend(RunEvent.Finished(failed("This Node has no npm.", started)))
            close()
            return@callbackFlow
        }

        spawn(
            plan = plan,
            directory = projectDir,
            started = started,
            whenItCannotStart = "npm could not start.",
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
        // that it went through the linker, because that is why `process.execPath`
        // will surprise them later.
        trySend(RunEvent.Started(plan.command.joinToString(" ")))

        // **Both streams, concurrently.** A process whose stderr fills while
        // only stdout is read blocks in the kernel and never exits -- the same
        // deadlock `NativeToolRunner` drains around, and the reason that loop
        // was not copied by hand a second time is that this one had to exist
        // anyway for a long-lived process.
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
     * process and the read then fails where it stands --
     * `InterruptedIOException: read interrupted`, or a closed stream. That is
     * the ordinary end of draining a cancelled run, not a defect, and letting
     * it escape crashes the collector that just asked the run to stop. Every
     * other failure would be the same shape and equally uninteresting: there is
     * no output left to deliver either way.
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
