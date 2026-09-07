package com.osamu.aide.ui.workspace

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.theme.CodeTextStyle
import kotlinx.coroutines.flow.filter

/** What the terminal panel can do, gathered so the dock can forward it. */
data class TerminalActions(
    val type: (String) -> Unit,
    val typeChar: (Char, Boolean) -> Unit,
    val sendKey: (Int) -> Unit,
    val interrupt: () -> Unit,
    val restart: () -> Unit,
    /**
     * How many cells fit, which only the view knows.
     *
     * `TerminalViewModel.resize` has existed since the terminal landed and its
     * KDoc says it is "driven by the view" -- and nothing drove it. The
     * emulator kept its default width while the panel showed about forty-six
     * columns, so a shell prompt that is an absolute path ran off the right
     * edge and everything typed after it was invisible until the user scrolled.
     */
    val resize: (Int, Int) -> Unit,
)

/**
 * A terminal, rendered from the vendored emulator's screen.
 *
 * **Characters go through as they are typed**, not a line at a time: the
 * emulator can interpret what comes back, so an interactive program receives
 * input while it is running. The field is kept empty and every change is
 * forwarded, which is what makes it behave like a keyboard rather than a form.
 *
 * A row of keys a soft keyboard does not have -- Esc, Tab, Ctrl, the arrows --
 * sits above it, because without them a phone cannot drive anything that reads
 * more than plain text.
 *
 * Attributes are not rendered. The emulator tracks colour, bold and inverse per
 * cell; this draws the characters in one style. `terminal/FINDINGS.md` records
 * that as the next piece of work rather than a limitation of the emulator.
 */
@Composable
fun TerminalPanel(
    state: TerminalUiState,
    actions: TerminalActions,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val focus = remember { FocusRequester() }
    var control by remember { mutableStateOf(false) }

    /** Emptied after every send; see the field below for why that matters. */
    val typing = rememberTextFieldState()

    // Follow the output the way a terminal does. Keyed on length so an
    // unchanged screen does not re-scroll on every recomposition.
    LaunchedEffect(state.screen.text.length) { vertical.animateScrollTo(vertical.maxValue) }

    // One cell, measured rather than assumed: the code font's advance is what
    // decides how many columns fit.
    //
    // Measured over a **run** and divided, not from a single glyph. One "M" is
    // one advance with no gap after it, so it under-measures by whatever the
    // style puts between characters, and the emulator was told it had 51
    // columns where about 46 were visible -- close enough to look right and
    // wrong enough that the end of every line needed a scroll.
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) {
        val run = measurer.measure(SAMPLE, CodeTextStyle).size
        run.width.toFloat() / SAMPLE.length to run.height
    }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { size ->
                    val (advance, lineHeight) = cell
                    if (advance > 0f && lineHeight > 0) {
                        actions.resize(
                            (size.width / advance).toInt().coerceAtLeast(MIN_COLUMNS),
                            (size.height / lineHeight).coerceAtLeast(MIN_ROWS),
                        )
                    }
                }
                .verticalScroll(vertical)
                .horizontalScroll(horizontal),
        ) {
            Text(
                text = state.screen.text.ifEmpty { "Starting a shell…" },
                style = CodeTextStyle,
                softWrap = false,
                modifier = Modifier.semantics { contentDescription = "Terminal output" },
            )
        }

        when (val shell = state.state) {
            is com.osamu.aide.terminal.TerminalState.Exited ->
                ExitedRow(shell.status, actions.restart)
            is com.osamu.aide.terminal.TerminalState.Failed -> Text(
                text = shell.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        // Forwarded from a snapshot rather than from a callback so that the
        // clear below is an ordinary state edit: two characters arriving
        // before this runs are collected as one string, which is what the
        // shell would have received anyway.
        //
        // Keyed on the state alone, with the actions read through
        // `rememberUpdatedState`, so that a caller which rebuilds them cannot
        // restart the collector and drop a keystroke mid-word.
        val currentActions = rememberUpdatedState(actions)
        LaunchedEffect(typing) {
            snapshotFlow { typing.text.toString() }
                .filter { it.isNotEmpty() }
                .collect { typed ->
                    if (control && typed.length == 1) {
                        currentActions.value.typeChar(typed[0], true)
                        control = false
                    } else {
                        currentActions.value.type(typed)
                    }
                    typing.edit { replace(0, length, "") }
                }
        }

        KeyRow(
            control = control,
            onToggleControl = { control = !control },
            enabled = state.isRunning,
            onKey = { code ->
                actions.sendKey(code)
                control = false
            },
            onInterrupt = actions.interrupt,
        )

        // **Held in a TextFieldState and cleared after every send.** The first
        // version passed a constant `TextFieldValue("")` as the value, on the
        // reasoning that a terminal's input is forwarded rather than kept. It
        // reads correctly and is wrong: Compose compares the value it is given
        // against the one it last sent to the IME, sees "" both times, and so
        // never tells the IME anything changed. The IME therefore keeps its own
        // buffer and re-sends the whole of it on every keystroke -- typing
        // `abcdef` reached the shell as `aababcabcdeef`, the running prefixes.
        //
        // Clearing a real state is what actually reaches the IME, and the
        // clear is done here rather than inside an input transformation so the
        // side effect stays out of the text pipeline.
        BasicTextField(
            state = typing,
            enabled = state.isRunning,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                autoCorrect = false,
                imeAction = ImeAction.None,
            ),
            // **No line limit.** A single-line field swallows Enter, and Enter
            // is how a shell is told to do anything: with it set, characters
            // reached the PTY and no command ever ran. The field is emptied
            // after every send, so it never grows regardless.
            decorator = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("> ", style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                    Box(Modifier.weight(1f)) {
                        field()
                        Text(
                            text = "type here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Terminal input" },
        )
    }
}

/**
 * The keys a soft keyboard does not have.
 *
 * Esc, Tab, the arrows and a sticky Ctrl. Every mobile terminal grows this row
 * for the same reason: without it there is no way to leave `vi`, complete a
 * path, or interrupt anything.
 */
@Composable
private fun KeyRow(
    control: Boolean,
    onToggleControl: () -> Unit,
    enabled: Boolean,
    onKey: (Int) -> Unit,
    onInterrupt: () -> Unit,
) {
    // Scrollable: the row is wider than a phone. Clipping it silently loses
    // whichever key is last, which was ^C -- the one nobody can do without.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = onToggleControl, enabled = enabled) {
            Text(
                text = "CTRL",
                style = MaterialTheme.typography.labelSmall,
                color = if (control) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(
            onClick = { onKey(KeyEvent.KEYCODE_ESCAPE) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Escape key" },
        ) { Text("ESC", style = MaterialTheme.typography.labelSmall) }
        TextButton(
            onClick = { onKey(KeyEvent.KEYCODE_TAB) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Tab key" },
        ) { Text("TAB", style = MaterialTheme.typography.labelSmall) }

        Arrow(Icons.Default.KeyboardArrowLeft, "Left", KeyEvent.KEYCODE_DPAD_LEFT, enabled, onKey)
        Arrow(Icons.Default.KeyboardArrowDown, "Down", KeyEvent.KEYCODE_DPAD_DOWN, enabled, onKey)
        Arrow(Icons.Default.KeyboardArrowUp, "Up", KeyEvent.KEYCODE_DPAD_UP, enabled, onKey)
        Arrow(Icons.Default.KeyboardArrowRight, "Right", KeyEvent.KEYCODE_DPAD_RIGHT, enabled, onKey)

        TextButton(
            onClick = onInterrupt,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Interrupt" },
        ) { Text("^C", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun Arrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    keyCode: Int,
    enabled: Boolean,
    onKey: (Int) -> Unit,
) {
    IconButton(
        onClick = { onKey(keyCode) },
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$name key" },
    ) { Icon(icon, contentDescription = null) }
}

@Composable
private fun ExitedRow(status: Int, onRestart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            // 128 + signal is how a shell reports being killed, and it means
            // something different from exiting with a status.
            text = if (status > 128) {
                "The shell was killed by signal ${status - 128}."
            } else {
                "The shell exited with status $status."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRestart,
            modifier = Modifier.semantics { contentDescription = "Restart the shell" },
        ) { Text("Restart") }
    }
}

/**
 * Floors for a terminal too small to be one.
 *
 * A zero-column emulator is not a smaller terminal, it is a broken one: curses
 * programs divide by the width. These are the smallest sizes anything sane
 * still runs at, and they only apply while the panel is being laid out.
 */
/** Long enough that the per-character gap is averaged rather than guessed. */
private const val SAMPLE = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"

private const val MIN_COLUMNS = 20
private const val MIN_ROWS = 4
