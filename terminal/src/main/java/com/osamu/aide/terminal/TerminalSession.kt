package com.osamu.aide.terminal

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * What a running shell is doing.
 *
 * [Exited] carries the status a terminal would print, which is the shell's own
 * exit code or `128 + signal` when something killed it. That distinction is
 * worth keeping: a shell that exited 0 and one that was killed by the system
 * look identical in a scrollback and mean opposite things.
 */
sealed interface TerminalState {
    data object Starting : TerminalState
    data object Running : TerminalState
    data class Exited(val status: Int) : TerminalState
    data class Failed(val reason: String) : TerminalState
}

/**
 * A shell on a pseudoterminal, with its output as a flow.
 *
 * **Bytes, not text.** Output is emitted as it arrives from the fd, in whatever
 * chunks the kernel produced, and a multi-byte character can be split across
 * two of them. Decoding is the emulator's job and it needs to hold the partial
 * sequence; a session that decoded eagerly would corrupt every UTF-8 character
 * that landed on a read boundary and do it rarely enough to look like a font
 * problem.
 *
 * The reading thread is a plain blocking read on [DispatcherProvider.io],
 * because that is what a terminal fd offers: no EOF until the shell exits, and
 * no non-blocking mode that would help. It ends when the shell does.
 *
 * **Not a terminal emulator.** There is no screen, no cursor and no escape
 * parsing here. `tools/pty/FINDINGS.md` section 9 records that the emulator is
 * the larger half of the work and a separate decision.
 */
class TerminalSession(
    private val dispatchers: DispatcherProvider,
    private val workingDirectory: File? = null,
    private val shell: String = Pty.DEFAULT_SHELL,
) : Closeable {

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Starting)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /**
     * Output as it arrives.
     *
     * Replay of zero and a bounded buffer that **drops the oldest** on
     * overflow. A terminal that back-pressured its shell would hang the shell
     * whenever the UI fell behind -- `cat` of a large file is the ordinary case
     * -- and a scrollback missing its oldest lines is the right thing to lose.
     */
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = OUTPUT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    private var pty: Pty? = null
    private var reader: Job? = null

    /**
     * Starts the shell and begins reading.
     *
     * [scope] owns the reading coroutine, so cancelling it stops the pump --
     * but **not** the shell, which is a separate process and is stopped by
     * [close]. Keeping those apart is deliberate: a screen going away is not a
     * reason to kill a build running in the terminal.
     */
    fun start(scope: CoroutineScope, columns: Int = 80, rows: Int = 24): AppResult<Unit> {
        if (pty != null) return AppResult.Failure(AppError("This session is already running."))

        val opened = runCatching {
            Pty.open(
                shell = shell,
                workingDirectory = workingDirectory?.absolutePath,
                columns = columns,
                rows = rows,
            )
        }.getOrElse { failure ->
            val reason = "Could not start a shell: ${failure.message}"
            _state.value = TerminalState.Failed(reason)
            return AppResult.Failure(AppError(reason, failure))
        }

        pty = opened
        _state.value = TerminalState.Running
        reader = scope.launch { pump(opened) }
        return AppResult.Success(Unit)
    }

    private suspend fun pump(pty: Pty) = withContext(dispatchers.io) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (true) {
                // Blocking, and there is no alternative worth having: a
                // terminal fd has no EOF until the shell exits and no
                // non-blocking mode that would let this poll usefully.
                val read = pty.output.read(buffer)
                if (read <= 0) break
                _output.emit(buffer.copyOf(read))
            }
        } catch (_: java.io.IOException) {
            // The far end closed, which is how a shell exiting reaches a reader
            // blocked on the master fd. Not an error -- the status below is.
        }
        _state.value = TerminalState.Exited(pty.waitFor())
    }

    /** Sends [text] as if typed. */
    suspend fun write(text: String) = write(text.toByteArray())

    suspend fun write(bytes: ByteArray) = withContext(dispatchers.io) {
        val open = pty ?: return@withContext
        runCatching {
            open.input.write(bytes)
            open.input.flush()
        }
    }

    /**
     * Tells the shell how big its terminal is.
     *
     * Which sends `SIGWINCH`, and is the only way a full-screen program learns
     * it has room. A terminal that never reports a size leaves every such
     * program believing it has 80x24 forever.
     */
    fun resize(columns: Int, rows: Int): AppResult<Unit> {
        val open = pty ?: return AppResult.Failure(AppError("No shell is running."))
        val result = open.resize(columns, rows)
        return if (result == 0) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(AppError("Could not resize the terminal: errno ${-result}"))
        }
    }

    /**
     * Interrupts whatever is in the foreground, the way Ctrl-C does.
     *
     * **To the foreground process group, not to the shell.** Signalling the
     * shell is the mistake that makes an interrupt look like it works: the
     * shell survives SIGINT and the running command never dies.
     * `tools/pty/FINDINGS.md` section 2.
     */
    fun interrupt(): AppResult<Unit> {
        val open = pty ?: return AppResult.Failure(AppError("No shell is running."))
        val group = open.foregroundGroup()
        if (group <= 0) {
            return AppResult.Failure(AppError("Could not read the foreground group."))
        }
        val result = open.signalGroup(group, SIGINT)
        return if (result == 0) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(AppError("Could not interrupt: errno ${-result}"))
        }
    }

    /**
     * Stops the shell and the reader.
     *
     * `SIGKILL` rather than a polite `SIGHUP`, because a shell blocked reading
     * its terminal does not always exit when the master closes, and a leaked
     * shell outlives the process that started it.
     */
    override fun close() {
        pty?.let {
            it.signal(SIGKILL)
            it.close()
        }
        pty = null
        reader?.cancel()
        reader = null
    }

    private companion object {
        const val SIGINT = 2
        const val SIGKILL = 9

        /** One read's worth. Terminal writes are small and frequent. */
        const val READ_BUFFER = 8 * 1024

        /**
         * Chunks buffered before the oldest is dropped.
         *
         * Sized for a UI that is briefly behind, not for a scrollback: holding
         * the whole of `cat` of a large file is the emulator's decision to
         * make, and it can make it because it sees every chunk this emits.
         */
        const val OUTPUT_BUFFER = 256
    }
}
