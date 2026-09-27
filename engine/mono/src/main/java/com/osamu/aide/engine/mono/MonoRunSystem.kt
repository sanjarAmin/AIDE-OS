package com.osamu.aide.engine.mono

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.RunEvent
import com.osamu.aide.engine.api.RunRequest
import com.osamu.aide.engine.api.RunResult
import com.osamu.aide.engine.api.RunStream
import com.osamu.aide.engine.api.RunSystem
import com.osamu.aide.toolchain.nativetools.LaunchPlan
import com.osamu.aide.toolchain.nativetools.MonoToolchain
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Compiles and runs a C# project.
 *
 * **Two processes, one run.** `mcs` is itself an assembly, so compiling means
 * starting the runtime with the compiler as its argument, and then starting the
 * runtime again with the result. Both are reported: the flow emits a
 * [RunEvent.Started] for each, because a reader looking at a `CS0103` needs to
 * see that it came from the compiler and not from their program.
 *
 * A compile failure ends the run as [RunResult.Failed] rather than
 * [RunResult.Exited] with `mcs`'s exit code. `Exited(1)` would say the program
 * ran and returned 1, which is a different thing that happens to look the same
 * in a status line -- and the compiler's own errors are already in the log by
 * then.
 *
 * @param workspace where the compiled assembly and mono's rewritten config go.
 *   It must be writable; the installed toolchain's own directory is not.
 */
class MonoRunSystem(
    private val mono: MonoToolchain,
    private val dispatchers: DispatcherProvider,
    private val workspace: File,
) : RunSystem {

    /**
     * True when there is a runtime and at least one source to compile.
     *
     * [RunRequest.entryPoint] is not consulted the way `:engine:node` consults
     * it: C# has no single entry file to run, it has an assembly built from
     * every source in the project, and `mcs` finds `Main` itself.
     */
    override fun handles(request: RunRequest): Boolean =
        mono.isInstalled && sourcesOf(request).isNotEmpty()

    override fun run(request: RunRequest): Flow<RunEvent> = channelFlow {
        val started = System.currentTimeMillis()

        if (!mono.isInstalled) {
            send(RunEvent.Finished(failed("Mono is not installed.", started)))
            return@channelFlow
        }
        val sources = sourcesOf(request)
        if (sources.isEmpty()) {
            send(
                RunEvent.Finished(
                    failed("No C# sources in ${request.projectDir.absolutePath}.", started),
                ),
            )
            return@channelFlow
        }

        val assembly = ProjectLayout(request.projectDir).csharpAssembly
        assembly.parentFile?.mkdirs()
        workspace.mkdirs()

        val compiled = execute(
            plan = mono.planCompile(
                sources = sources,
                output = assembly,
                workspace = workspace,
            ),
            directory = request.projectDir,
        )
        if (compiled != 0) {
            // Named rather than counted: mcs has already printed every error,
            // and a second opinion on how many there were would only disagree.
            // Null is the compiler failing to start, which is not the user's
            // code being wrong and must not be reported as if it were.
            val why = if (compiled == null) "The compiler could not start." else "Compilation failed."
            send(RunEvent.Finished(failed(why, started)))
            return@channelFlow
        }

        val exit = execute(
            plan = mono.planRun(
                assembly = assembly,
                workspace = workspace,
                arguments = request.arguments,
            ),
            directory = request.projectDir,
        )
        send(
            RunEvent.Finished(
                if (exit == null) {
                    failed("The program could not start.", started)
                } else {
                    RunResult.Exited(exit, System.currentTimeMillis() - started)
                },
            ),
        )
    }.flowOn(dispatchers.io)

    private fun sourcesOf(request: RunRequest): List<File> =
        ProjectLayout(request.projectDir).csharpSources()

    /**
     * Runs one process to completion, forwarding both its streams.
     *
     * Null when it could not be started at all, which is not an exit code and
     * must not be turned into one -- `Exited(-1)` would claim the program ran.
     *
     * [runInterruptible] rather than a plain `waitFor`: a blocking wait ignores
     * coroutine cancellation, so a stopped run would go on holding the process
     * until the program chose to exit -- which for anything long-lived is
     * never. Interrupting the wait raises out of `waitFor`, and the `finally`
     * then kills what it was waiting for.
     */
    private suspend fun ProducerScope<RunEvent>.execute(
        plan: LaunchPlan,
        directory: File,
    ): Int? {
        val process = try {
            ProcessBuilder(plan.command)
                .directory(directory)
                .apply { environment().putAll(plan.environment) }
                .start()
        } catch (failure: Exception) {
            send(RunEvent.Output(failure.message ?: "mono could not start.", RunStream.STDERR))
            return null
        }

        // The whole command, linker and all: it is why the process this reports
        // is `linker64` and not `mono`, which is otherwise a mystery in a log.
        send(RunEvent.Started(plan.command.joinToString(" ")))

        // Both streams concurrently. A process whose stderr fills while only
        // stdout is read blocks in the kernel and never exits -- and mcs writes
        // its diagnostics to stdout, so neither stream can be assumed idle.
        val drains = listOf(
            launch(dispatchers.io) { drain(process.inputStream, RunStream.STDOUT) },
            launch(dispatchers.io) { drain(process.errorStream, RunStream.STDERR) },
        )

        try {
            return runInterruptible(dispatchers.io) { process.waitFor() }
        } finally {
            process.destroyForcibly()
            // Joined even when cancelled, so that everything the process said
            // has been sent before the caller sees the run end. Without it a
            // compiler error can arrive after "Compilation failed", which reads
            // as a second failure.
            withContext(NonCancellable) { drains.joinAll() }
        }
    }

    /**
     * Forwards one stream until it ends **or the process is killed under it**.
     *
     * The read is not cancellable, so stopping a run destroys the process and
     * the read then fails where it stands -- `InterruptedIOException: read
     * interrupted`, or a closed stream. That is the ordinary end of draining a
     * cancelled run and not a defect; there is no output left to deliver.
     */
    private fun ProducerScope<RunEvent>.drain(stream: InputStream, tag: RunStream) {
        runCatching {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { trySend(RunEvent.Output(it, tag)) }
            }
        }
    }

    private fun failed(message: String, started: Long) =
        RunResult.Failed(message, System.currentTimeMillis() - started)
}
