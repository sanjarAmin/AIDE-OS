package com.osamu.aide.terminal

import android.view.KeyEvent
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * What the screen looks like right now.
 *
 * An immutable snapshot rather than a handle on the emulator. The emulator's
 * buffer is mutable and is written from the thread that parses output; handing
 * the UI a reference to it would mean rendering a screen that changes underneath
 * the renderer.
 */
data class TerminalScreen(
    val text: String,
    val cursorRow: Int,
    val cursorColumn: Int,
    val columns: Int,
    val rows: Int,
)

/**
 * A shell, an emulator, and the wiring between them.
 *
 * [TerminalSession] moves bytes; `com.termux.terminal.TerminalEmulator`
 * interprets them into a screen. This owns both and is the only thing that
 * touches the emulator.
 *
 * **Everything emulator-related happens on one thread.** `TerminalEmulator` is
 * not thread-safe -- it is a parser with a mutable screen behind it -- and the
 * bytes arrive on an IO thread while the UI reads on the main one. Confining it
 * to a single dispatcher and publishing immutable [TerminalScreen] snapshots is
 * what makes that safe without a lock the renderer would have to hold.
 *
 * The vendored emulator is Apache 2.0 and unmodified; `terminal/vendor/PROVENANCE.md`
 * records where it came from and what was left behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmulatedTerminal(
    private val dispatchers: DispatcherProvider,
    workingDirectory: File? = null,
    private val transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
) : Closeable {

    private val session = TerminalSession(dispatchers, workingDirectory)

    /**
     * The one thread the emulator is ever touched from.
     *
     * `limitedParallelism(1)` rather than a dedicated thread: it borrows from
     * the shared pool, so an idle terminal costs nothing, and the confinement
     * guarantee is the same.
     */
    private val emulatorThread: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val _screen = MutableStateFlow(TerminalScreen("", 0, 0, 80, 24))
    val screen: StateFlow<TerminalScreen> = _screen.asStateFlow()

    val state: StateFlow<TerminalState> get() = session.state

    /** Set once [start] has run; only ever touched on [emulatorThread]. */
    private var emulator: TerminalEmulator? = null

    /**
     * Where the emulator writes back to.
     *
     * The shell has to hear about a lot of this: a cursor position report, a
     * device attributes answer, bracketed paste. Dropping these is what makes a
     * terminal feel subtly broken -- a program waits for an answer that never
     * comes and simply hangs.
     */
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            scope?.launch { session.write(data.copyOfRange(offset, offset + count)) }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            _title.value = newTitle.orEmpty()
        }

        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val client = object : TerminalSessionClient {
        override fun getTerminalCursorStyle(): Int? = null
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
    }

    private val _title = MutableStateFlow("")

    /** The title the shell set with an OSC sequence, if any. */
    val title: StateFlow<String> = _title.asStateFlow()

    private var scope: CoroutineScope? = null

    /** Starts the shell and the emulator at [columns] x [rows]. */
    fun start(scope: CoroutineScope, columns: Int = 80, rows: Int = 24): AppResult<Unit> {
        this.scope = scope

        scope.launch(emulatorThread) {
            emulator = TerminalEmulator(
                output,
                columns,
                rows,
                CELL_WIDTH_PX,
                CELL_HEIGHT_PX,
                transcriptRows,
                client,
            )
            publish()
        }

        scope.launch {
            session.output.collect { chunk ->
                withContext(emulatorThread) {
                    emulator?.append(chunk, chunk.size)
                    publish()
                }
            }
        }

        return session.start(scope, columns, rows)
    }

    /** Types [text], as a paste would. */
    suspend fun write(text: String) = session.write(text)

    /**
     * Sends a key press the way a terminal does.
     *
     * `KeyHandler` is the vendored mapping from an Android key code to the
     * bytes a terminal expects, and it needs the emulator's **modes** to do it:
     * an arrow key is a different sequence in application cursor mode, and the
     * numeric keypad is a different sequence again in application keypad mode.
     * Hard-coding the common case is how a terminal ends up almost working in
     * `vi`.
     */
    suspend fun sendKey(keyCode: Int, controlDown: Boolean = false, altDown: Boolean = false) {
        val bytes = withContext(emulatorThread) {
            val active = emulator ?: return@withContext null
            KeyHandler.getCode(
                keyCode,
                modifiers(controlDown, altDown),
                active.isCursorKeysApplicationMode,
                active.isKeypadApplicationMode,
            )
        } ?: return
        session.write(bytes)
    }

    /**
     * Sends a printable character, applying Ctrl if held.
     *
     * Ctrl is arithmetic rather than a lookup: `Ctrl-A` is 1, `Ctrl-C` is 3.
     * That is what makes an interrupt typed at the keyboard reach the shell as
     * a real `^C` instead of being routed round the side.
     */
    suspend fun sendChar(character: Char, controlDown: Boolean = false) {
        val code = when {
            !controlDown -> character.code
            character.uppercaseChar() in 'A'..'_' -> character.uppercaseChar().code - 64
            character == ' ' -> 0
            else -> character.code
        }
        session.write(String(Character.toChars(code)).toByteArray(StandardCharsets.UTF_8))
    }

    /** Resizes both halves: the emulator's screen and the shell's idea of it. */
    suspend fun resize(columns: Int, rows: Int) {
        if (columns < 2 || rows < 2) return
        withContext(emulatorThread) {
            emulator?.resize(columns, rows, CELL_WIDTH_PX, CELL_HEIGHT_PX)
            publish()
        }
        session.resize(columns, rows)
    }

    /** Ctrl-C to the foreground process group; see [TerminalSession.interrupt]. */
    fun interrupt(): AppResult<Unit> = session.interrupt()

    override fun close() = session.close()

    /**
     * Reads the screen into a snapshot.
     *
     * **Only ever called on [emulatorThread].** The scrollback is included by
     * starting at a negative row, which is how the vendored buffer addresses
     * lines that have scrolled off.
     */
    private fun publish() {
        val active = emulator ?: return
        val screen = active.screen
        val top = -screen.activeTranscriptRows
        // **Trailing blank lines are trimmed.** The emulator's screen is a fixed
        // `rows` tall and pads the unused part, so a fresh shell reads as one
        // prompt followed by twenty-three empty lines. A view that scrolls to
        // the bottom of that shows the padding and looks like a terminal that
        // printed nothing -- which is exactly how this first appeared in the
        // running app.
        //
        // Only the trailing run: blank lines in the middle are the shell's own
        // output and are content.
        val text = screen
            .getSelectedText(0, top, active.mColumns - 1, active.mRows - 1)
            .trimEnd('\n', ' ')
        _screen.value = TerminalScreen(
            text = text,
            cursorRow = active.cursorRow,
            cursorColumn = active.cursorCol,
            columns = active.mColumns,
            rows = active.mRows,
        )
    }

    private fun modifiers(controlDown: Boolean, altDown: Boolean): Int {
        var mask = 0
        if (controlDown) mask = mask or KeyEvent.META_CTRL_ON
        if (altDown) mask = mask or KeyEvent.META_ALT_ON
        return mask
    }

    private companion object {
        /**
         * Reported to the shell for pixel-size queries (`CSI 14 t`). Nothing
         * renders at these; a program that asks gets a plausible answer rather
         * than a zero it might divide by.
         */
        const val CELL_WIDTH_PX = 12
        const val CELL_HEIGHT_PX = 24

        /** Lines kept after they scroll off. Termux's own default is 2000. */
        const val DEFAULT_TRANSCRIPT_ROWS = 2000
    }
}
