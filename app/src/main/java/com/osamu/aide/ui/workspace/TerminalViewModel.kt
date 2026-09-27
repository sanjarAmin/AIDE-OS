package com.osamu.aide.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.terminal.EmulatedTerminal
import com.osamu.aide.terminal.TerminalScreen
import com.osamu.aide.terminal.TerminalState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TerminalUiState(
    val screen: TerminalScreen = TerminalScreen("", 0, 0, 80, 24),
    val title: String = "",
    val state: TerminalState = TerminalState.Starting,
    val errorMessage: String? = null,
) {
    val isRunning: Boolean get() = state is TerminalState.Running
    val output: String get() = screen.text
}

/**
 * A shell with a real terminal emulator behind it.
 *
 * The emulator is Termux's, vendored unmodified;
 * `terminal/vendor/PROVENANCE.md` records where it came from. This owns an
 * [EmulatedTerminal] and republishes its screen, which is already an immutable
 * snapshot -- the emulator is confined to its own thread and nothing here
 * touches it.
 *
 * **Input goes through as it is typed**, character by character, rather than a
 * line at a time. That is what the emulator buys: before it, sending a
 * keystroke was pointless because nothing could interpret the response, so the
 * panel composed whole lines. Now `vi` receives a `j` when the user types one.
 */
class TerminalViewModel(
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var terminal: EmulatedTerminal? = null
    private var projectDir: File? = null

    /**
     * Every keystroke, in the order it was typed.
     *
     * **Not one coroutine per key.** Launching each send independently means
     * they race: typing `xy`, pressing Left and typing `Z` produced `xyZ`
     * instead of `xZy`, because the arrow key arrived after the letter it was
     * supposed to precede. A terminal that reorders input is not subtly wrong,
     * it is unusable -- and the reordering is invisible until something depends
     * on sequence, which is why a test caught it and typing at it would not
     * have.
     *
     * Unbounded because dropping a keystroke is never the right answer, and a
     * human cannot outrun a shell.
     *
     * **One channel per shell**, replaced on restart. Reusing it left the old
     * consumer running against a closed terminal, competing for events with the
     * new one -- so after a restart roughly half of what was typed went to a
     * dead shell and simply vanished.
     */
    private var input = Channel<TerminalInput>(Channel.UNLIMITED)

    /** One keystroke's worth of input, in the form the emulator wants it. */
    private sealed interface TerminalInput {
        data class Text(val text: String) : TerminalInput
        data class Char(val character: kotlin.Char, val control: Boolean) : TerminalInput
        data class Key(val code: Int, val control: Boolean, val alt: Boolean) : TerminalInput
    }

    /**
     * Starts a shell in [directory], once.
     *
     * Guarded like `GitViewModel.open`, and for the same reason: this is called
     * from a `LaunchedEffect` that re-runs on every configuration change, and a
     * second start would leave the first shell running with nothing reading it.
     */
    fun open(directory: File) {
        projectDir = directory
        if (terminal != null) return
        start()
    }

    private fun start() {
        val directory = projectDir ?: return
        val started = EmulatedTerminal(dispatchers, workingDirectory = directory)
        terminal = started

        viewModelScope.launch {
            started.screen.collect { screen -> _state.update { it.copy(screen = screen) } }
        }
        viewModelScope.launch {
            started.state.collect { state -> _state.update { it.copy(state = state) } }
        }
        viewModelScope.launch {
            started.title.collect { title -> _state.update { it.copy(title = title) } }
        }

        // One consumer per shell, so input reaches it in the order it was
        // typed and stops when that shell does.
        val events = Channel<TerminalInput>(Channel.UNLIMITED)
        input = events
        viewModelScope.launch {
            for (event in events) {
                when (event) {
                    is TerminalInput.Text -> started.write(event.text)
                    is TerminalInput.Char -> started.sendChar(event.character, event.control)
                    is TerminalInput.Key -> started.sendKey(event.code, event.control, event.alt)
                }
            }
        }

        when (val result = started.start(viewModelScope)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
        }
    }

    /** Sends typed text straight through, unmodified. */
    fun type(text: String) {
        input.trySend(TerminalInput.Text(text))
    }

    /** Sends one character, with Ctrl applied if held. */
    fun typeChar(character: Char, controlDown: Boolean = false) {
        input.trySend(TerminalInput.Char(character, controlDown))
    }

    /**
     * Sends a key by Android key code, mapped through the vendored
     * `KeyHandler` using the emulator's current modes.
     */
    fun sendKey(keyCode: Int, controlDown: Boolean = false, altDown: Boolean = false) {
        input.trySend(TerminalInput.Key(keyCode, controlDown, altDown))
    }

    /**
     * Tells both halves how big the terminal is.
     *
     * Driven by the view, which is the only thing that knows how many
     * monospace cells fit. A terminal whose emulator and shell disagree about
     * the width wraps lines in the wrong place.
     */
    fun resize(columns: Int, rows: Int) {
        val shell = terminal ?: return
        viewModelScope.launch { shell.resize(columns, rows) }
    }

    /** Ctrl-C to the foreground process group, for the toolbar button. */
    fun interrupt() {
        val shell = terminal ?: return
        when (val result = shell.interrupt()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
        }
    }

    /** Starts a new shell after one has exited. */
    fun restart() {
        input.close()
        terminal?.close()
        terminal = null
        _state.update { it.copy(state = TerminalState.Starting, errorMessage = null) }
        start()
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    /**
     * Kills the shell.
     *
     * Public rather than left to [onCleared], which is `protected` and cannot
     * be called deterministically. This view model owns a **child process**,
     * and a test that cannot stop one leaks a shell per test.
     */
    fun shutdown() {
        input.close()
        terminal?.close()
        terminal = null
    }

    override fun onCleared() = shutdown()
}
